package newhope.i2c

import spinal.core._
import spinal.core.sim._
import newhope.vertebra.Characterization._
import newhope.vertebra.sim.{SimBackend, SimEnv}

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
//    sbt "Test/runMain newhope.i2c.FilterSweep --model"
//    sbt "Test/runMain newhope.i2c.FilterSweep --jobs 4"
//    sbt "Test/runMain newhope.i2c.FilterSweep --check filter_sweep.baseline.csv"
//    sbt "Test/runMain newhope.i2c.FilterSweep --merge a.csv,b.csv --out all.csv"
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
//    sbt "Test/runMain newhope.i2c.FilterSweep --w 1:4 --out a.csv" &
//    sbt "Test/runMain newhope.i2c.FilterSweep --w 5:8 --out b.csv" &
//    wait
//    sbt "Test/runMain newhope.i2c.FilterSweep --merge a.csv,b.csv --out all.csv"
//  Wolniejszy start (drugi JVM), za to zero pytan o wspoldzielony stan.
// =====================================================================
object FilterSweep {

  private val flags = Set("--wave", "--no-wave", "--model")

  private def parse(args : Array[String]) : (Map[String, String], Set[String]) = {
    val opts = collection.mutable.Map[String, String]()
    val set  = collection.mutable.Set[String]()
    var i = 0
    while (i < args.length) {
      val a = args(i)
      require(a.startsWith("--"), s"nieoczekiwany argument: $a")
      if (flags.contains(a)) { set += a; i += 1 }
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
                      wave : Boolean,
                      backend   : SimBackend) : (Cell, I2cSmoke.Result) = {
    // Zamiast liczyc sclFrequency = 100e6/(4q) i modlic sie o zaokraglenie
    // w (clk/scl/4).toInt - dobieramy zegar systemowy. 4q MHz / 1 MHz / 4
    // == q dokladnie, bez zmiennoprzecinkowej loterii.
    val g = try I2cGenerics(clkFrequency = (4 * q) MHz,
                        sclFrequency = 1 MHz,
                        filterWindow = w)
            catch { case e: Throwable =>
              return (Cell(Seq("w" -> w, "q" -> q), "C"),
              I2cSmoke.Result(false, false, false, compiled = false,
                              note = s"${e.getClass.getSimpleName}: ${e.getMessage}"))
            }
    require(g.quarterCycles == q, s"zaokraglenie: chcialem q=$q, wyszlo ${g.quarterCycles}")

    val r = I2cSmoke.run(g, build, s"sweep_${impl}_w${w}_q$q", wave, backend)
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
    val waveOverride : Option[Boolean] =
      (flag("--wave"), flag("--no-wave")) match {
        case (true, true) => throw new IllegalArgumentException("--wave i --no-wave naraz")
        case (true, _)    => Some(true)
        case (_, true)    => Some(false)
        case _            => None
      }
    val wave = waveOverride.getOrElse(SimEnv.waves)
    val backend = SimBackend.parse(opt.getOrElse("--backend", "verilator"))

    val cores = Runtime.getRuntime.availableProcessors
    val jobs  = math.max(1, math.min(opt.getOrElse("--jobs", "1").toInt, cores))

    def instrument(d : I2cPhyBase) : I2cPhyBase = {
      d.stretching.simPublic()
      d.filter.scl.simPublic()
      d.filter.sda.simPublic()
      d
    }
    val build : I2cGenerics => I2cPhyBase = impl match {
      case "table" => g => instrument(I2cPhyTable(g))
      case "fsm"   => g => instrument(I2cPhyFsm(g))
      case other   => throw new IllegalArgumentException(s"--impl table|fsm, nie $other")
    }

    val points = (for (w <- ws; q <- qs) yield (w, q)).toSeq
    println(s"sweep $impl: w in $ws, q in $qs -> ${points.size} kompilacji ${backend.label}, "
          + s"jobs=$jobs (rdzeni: $cores)")
    if (jobs > 1)
      println(s"UWAGA: ${backend.label} + g++ same biora pamiec; licz ok. 1-2 GB na job")
    if (wave)
      println(s"UWAGA: --wave przy ${points.size} przebiegach to kilka GB FST")

    val t0   = System.currentTimeMillis()
    val done = new AtomicInteger(0)

    val results : Seq[(Cell, I2cSmoke.Result)] =
      if (jobs == 1) points.map { case (w, q) =>
        val res = runCell(w, q, impl, build, wave, backend)
        report(res, done.incrementAndGet(), points.size)
        res
      }
      else {
        val pool = Executors.newFixedThreadPool(jobs)
        implicit val ec : ExecutionContext = ExecutionContext.fromExecutorService(pool)
        try {
          val fs = points.map { case (w, q) => Future {
            val res = runCell(w, q, impl, build, wave, backend)
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

    if (flag("--model")) {
      val bad = results.collect { case (c, r) if r.compiled =>
        val Seq(w, q) = c.key
        val model = I2cGenerics((4 * q) MHz, 1 MHz, w).filterLatency
        (w, q, r.sdaLatency, model)
      }.filter { case (_, _, pomiar, model) => pomiar != model }

      println()
      if (bad.isEmpty) println("model opoznienia filtra zgadza sie z pomiarem")
      else {
        println(s"MODEL SIE NIE ZGADZA w ${bad.size} komorkach:")
        bad.foreach { case (w, q, p, m) => println(s"  w=$w q=$q: pomiar=$p, model=$m") }
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
