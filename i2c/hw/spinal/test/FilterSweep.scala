package org.newhope.i2c

import spinal.core._
import org.newhope.vertebra.Characterization._

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

// =====================================================================
//  CHARAKTERYZACJA: gdzie lezy granica filtr vs cwiartka.
//
//  To NIE jest test i celowo nie jest w zadnej suicie. Pytanie
//  regresyjne mieszka w testplanie jako filter_window_vs_quarter_boundary
//  i kosztuje cztery kompilacje, nie piecdziesiat szesc.
//
//    sbt "Test/runMain mylib.i2c.FilterSweep --model"
//    sbt "Test/runMain mylib.i2c.FilterSweep --jobs 4"
//    sbt "Test/runMain mylib.i2c.FilterSweep --check filter_sweep.baseline.csv"
//    sbt "Test/runMain mylib.i2c.FilterSweep --merge a.csv,b.csv --out all.csv"
//
//  ZROWNOLEGLENIE
//  --------------
//  sbt-owe `Test / parallelExecution` NIE dziala na runMain - to
//  ustawienie dla suit testowych. Musi byc w srodku aplikacji.
//
//  Domyslnie --jobs 1, bo zrownoleglenie w JVM zaklada, ze elaboracja
//  SpinalHDL jest bezpieczna watkowo (GlobalData per watek). Sprawdz
//  --jobs 2 na malym zakresie i porownaj CSV z przebiegiem sekwencyjnym,
//  ZANIM puscisz --jobs 8. Rozjazd == elaboracja dzieli stan.
//
//  Wariant bez tego zalozenia: shardowanie po PROCESACH.
//    sbt "Test/runMain mylib.i2c.FilterSweep --w 1:4 --out a.csv" &
//    sbt "Test/runMain mylib.i2c.FilterSweep --w 5:8 --out b.csv" &
//    wait
//    sbt "Test/runMain mylib.i2c.FilterSweep --merge a.csv,b.csv --out all.csv"
//  Wolniejszy start (drugi JVM), za to zero pytan o wspoldzielony stan.
// =====================================================================
object FilterSweep {

  private val flags = Set("--wave", "--model")

  private def parse(args : Array[String]) : (Map[String, String], Set[String]) = {
    val opts = collection.mutable.Map[String, String]()
    val set  = collection.mutable.Set[String]()
    var i = 0
    while (i < args.length) {
      val a = args(i)
      require(a.startsWith("--"), s"nieoczekiwany argument: $a")
      if (flags(a)) { set += a; i += 1 }
      else {
        require(i + 1 < args.length, s"$a wymaga wartosci")
        opts(a) = args(i + 1); i += 2
      }
    }
    (opts.toMap, set.toSet)
  }

  private def range(s : String) : Range = s.split(":") match {
    case Array(a, b) => a.toInt to b.toInt
    case Array(a)    => a.toInt to a.toInt
    case _           => throw new IllegalArgumentException(s"zly zakres: $s")
  }

  /** Jedna komorka: kompilacja + przebieg. Bezstanowa poza workspace'em,
    * ktory jest unikalny per (impl, w, q) - to jest warunek konieczny
    * zrownoleglenia i jedyny, ktory moge zagwarantowac z tej strony. */
  private def runCell(w : Int, q : Int, impl : String,
                      build : I2cGenerics => I2cPhyBase,
                      wave : Boolean) : (Cell, I2cSmoke.Result) = {
    // Zamiast liczyc sclFrequency = 100e6/(4q) i modlic sie o zaokraglenie
    // w (clk/scl/4).toInt - dobieramy zegar systemowy. 4q MHz / 1 MHz / 4
    // == q dokladnie, bez zmiennoprzecinkowej loterii.
    val g = I2cGenerics(clkFrequency = (4 * q) MHz,
                        sclFrequency = 1 MHz,
                        filterWindow = w)
    require(g.quarterCycles == q, s"zaokraglenie: chcialem q=$q, wyszlo ${g.quarterCycles}")

    val r = I2cSmoke.run(g, build, s"sweep_${impl}_w${w}_q$q", wave)
    (Cell(Seq("w" -> w, "q" -> q), r.verdict), r)
  }

  def main(args : Array[String]) : Unit = {
    val (opt, flag) = parse(args)
    val out = opt.getOrElse("--out", "filter_sweep.csv")

    // --- tryb scalania shardow: bez symulacji, tylko pliki -----------
    opt.get("--merge").foreach { list =>
      val paths = list.split(",").map(_.trim).toSeq
      val merged = paths.map(p => Grid.fromCsv(p, p)).reduce(_ merge _)
      println(merged.render("w", "q"))
      merged.write(out)
      return
    }

    val ws   = range(opt.getOrElse("--w", "1:8"))
    val qs   = range(opt.getOrElse("--q", "2:8"))
    val impl = opt.getOrElse("--impl", "table")
    val wave = flag("--wave")

    val cores = Runtime.getRuntime.availableProcessors
    val jobs  = math.max(1, math.min(opt.getOrElse("--jobs", "1").toInt, cores))

    val build : I2cGenerics => I2cPhyBase = impl match {
      case "table" => g => I2cPhyTable(g)
      case "fsm"   => g => I2cPhyFsm(g)
      case other   => throw new IllegalArgumentException(s"--impl table|fsm, nie $other")
    }

    val points = (for (w <- ws; q <- qs) yield (w, q)).toSeq
    println(s"sweep $impl: w in $ws, q in $qs -> ${points.size} kompilacji Verilatora, "
          + s"jobs=$jobs (rdzeni: $cores)")
    if (jobs > 1)
      println("UWAGA: verilator + g++ same biora pamiec; licz ok. 1-2 GB na job")
    if (wave)
      println(s"UWAGA: --wave przy ${points.size} przebiegach to kilka GB FST")

    val t0   = System.currentTimeMillis()
    val done = new AtomicInteger(0)

    val results : Seq[(Cell, I2cSmoke.Result)] =
      if (jobs == 1) points.map { case (w, q) =>
        val res = runCell(w, q, impl, build, wave)
        report(res, done.incrementAndGet(), points.size)
        res
      }
      else {
        val pool = Executors.newFixedThreadPool(jobs)
        implicit val ec : ExecutionContext = ExecutionContext.fromExecutorService(pool)
        try {
          val fs = points.map { case (w, q) => Future {
            val res = runCell(w, q, impl, build, wave)
            // println jest zsynchronizowany na PrintStream, wiec cala
            // linia wychodzi w calosci; kolejnosc linii bedzie losowa.
            report(res, done.incrementAndGet(), points.size)
            res
          }}
          Await.result(Future.sequence(fs), Duration.Inf)
        } finally pool.shutdown()
      }

    val cells = results.map(_._1)
    val grid  = Grid(s"filter_sweep_$impl", cells)

    // Siatki metryk z FilterProbe. Ten sam typ Grid, wiec ida do CSV
    // i do baseline'u tak samo jak werdykt - to jest ta rzecz, ktorej
    // nie dalo sie dostac z FST bez pisania parsera.
    def metric(name : String, f : I2cSmoke.Result => Int) = Grid(name,
      results.map { case (c, r) => Cell(c.coords, f(r).toString) })

    val latency = metric("sda_latency", _.sdaLatency)
    val stretch = metric("stretch_cycles", _.stretchCycles)
    val mins  = (System.currentTimeMillis() - t0) / 60000.0

    println()
    println(grid.render(rowAxis = "w", colAxis = "q"))
    println()
    println(".=OK  R=zly odczyt  P=zly protokol  T=timeout  C=kompilacja padla")
    println(f"czas: $mins%.1f min")
    grid.write(out)

    // --- metryki: opoznienie filtra i falszywy stretching -------------
    // Model konstrukcyjny mowi L = w + 3 (BufferCC 2 + okno w + reg 1).
    // Jesli zmierzone L rosnie inaczej, model jest zly. Jesli
    // stretch_cycles rosnie z w, to Q1 jest rozciagane i okno na
    // ustalenie SDA nie wynosi 2q, tylko 2q + L_scl - co tlumaczyloby
    // nachylenie 3 zamiast 2.
    println()
    println("opoznienie filtra SDA (cykle, pin -> filter.sda):")
    println(latency.render(rowAxis = "w", colAxis = "q"))
    latency.write(out.replace(".csv", "") + ".latency.csv")

    println()
    println("cykle z aktywnym `stretching` (falszywy stretching):")
    println(stretch.render(rowAxis = "w", colAxis = "q"))
    stretch.write(out.replace(".csv", "") + ".stretch.csv")

    // --- czy pomiar zgadza sie z modelem analitycznym ----------------
    // Kalibracja stalej FilterModel.slack. Jesli tu cokolwiek wyjdzie,
    // popraw model, nie testpoint.
    if (flag("--model")) {
      val bad = cells.filter { c =>
        val Seq(w, q) = c.key
        (c.verdict == ".") != FilterModel.readOk(w, q)
      }
      println()
      if (bad.isEmpty) println(s"model (slack=${FilterModel.slack}) zgadza sie z pomiarem")
      else {
        println(s"MODEL SIE NIE ZGADZA w ${bad.size} komorkach:")
        bad.foreach { c =>
          val Seq(w, q) = c.key
          println(s"  w=$w q=$q: pomiar=${c.verdict}, model=${FilterModel.readOk(w, q)}")
        }
      }
    }

    // --- monotonicznosc ----------------------------------------------
    // Wlasnosc niezalezna od kalibracji: obszar OK domkniety w dol po w
    // i w gore po q. Niemonotonicznosc == drugi mechanizm awarii,
    // ktorego nie rozumiemy. To ciekawsza informacja niz sama granica.
    val ok = cells.filter(_.verdict == ".").map(c => (c.key(0), c.key(1))).toSet
    val breaks = for {
      (w, q) <- ok.toSeq.sorted
      bad    <- Seq((w - 1, q), (w, q + 1)).filter { case (w2, q2) =>
                  ws.contains(w2) && qs.contains(q2) && !ok((w2, q2)) }
    } yield s"  OK w=$w q=$q, ale NIE OK w=${bad._1} q=${bad._2}"
    if (breaks.nonEmpty) {
      println("\nNIEMONOTONICZNOSC (obszar OK nie jest domkniety):")
      breaks.foreach(println)
    }

    // --- regresja wzgledem baseline'u --------------------------------
    opt.get("--check").foreach { path =>
      val diff = grid.diff(Grid.fromCsv("baseline", path))
      if (diff.isEmpty) println(s"\nbez zmian wzgledem $path")
      else {
        println(s"\nROZJAZD wzgledem $path:")
        diff.foreach(d => println(s"  $d"))
        sys.exit(1)
      }
    }
  }

  private def report(res : (Cell, I2cSmoke.Result), n : Int, total : Int) : Unit = {
    val (c, r) = res
    val Seq(w, q) = c.key
    println(f"[$n%3d/$total%3d] w=$w%d q=$q%d -> ${r.verdict}%s ${if (r.ok) "" else r.note}%s")
  }
}
