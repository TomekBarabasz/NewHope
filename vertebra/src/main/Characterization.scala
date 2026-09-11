package org.newhope.vertebra

import java.nio.file.{Files, Paths}
import scala.io.Source

// =====================================================================
//  CHARAKTERYZACJA - trzeci tryb uruchomienia obok testow kierowanych
//  i fuzzingu.
//
//  Cecha wspolna: dlugi przebieg, wynikiem sa DANE, nie werdykt.
//  Werdykt powstaje dopiero z porownania z baseline'em. Dzieki temu
//  ta sama petla obsluguje sweep parametrow ("gdzie lezy granica")
//  i regresje w CI ("czy granica sie nie przesunela").
//
//  Docelowo: vertebra/core/src/main/scala/vertebra/Characterize.scala
//  Ten sam mechanizm ma obsluzyc baseline pokrycia z Fuzz.scala.
// =====================================================================
object Characterization {

  /** Jedna komorka przestrzeni parametrow. coords to nazwane osie,
    * verdict to krotki symbol - celowo String, a nie Boolean, zeby
    * dalo sie rozroznic RODZAJ awarii (patrz I2cSmoke.Result.verdict). */
  case class Cell(coords : Seq[(String, Int)], verdict : String) {
    def key  : Seq[Int]    = coords.map(_._2)
    def axes : Seq[String] = coords.map(_._1)
  }

  case class Grid(name : String, cells : Seq[Cell]) {
    require(cells.nonEmpty, "pusta charakteryzacja")
    val axes : Seq[String] = cells.head.axes
    require(cells.forall(_.axes == axes),
            s"komorki maja rozne osie: ${cells.map(_.axes).distinct}")

    private def byKey : Map[Seq[Int], String] =
      cells.map(c => c.key -> c.verdict).toMap

    def apply(k : Seq[Int]) : Option[String] = byKey.get(k)

    def toCsv : String = {
      val head = (axes :+ "verdict").mkString(",")
      val rows = cells
        .sortBy(_.key)(Ordering.Implicits.seqOrdering[Seq, Int])
        .map(c => (c.key.map(_.toString) :+ c.verdict).mkString(","))
      (head +: rows).mkString("\n") + "\n"
    }

    def write(path : String) : Unit = {
      Files.write(Paths.get(path), toCsv.getBytes("UTF-8"))
      println(s"zapisano $path (${cells.size} komorek)")
    }

    /** Siatka 2D do czytania okiem. Schodek widac od razu. */
    def render(rowAxis : String, colAxis : String) : String = {
      val ri = axes.indexOf(rowAxis)
      val ci = axes.indexOf(colAxis)
      require(ri >= 0 && ci >= 0, s"osie $rowAxis/$colAxis nie ma w $axes")

      val rows = cells.map(_.key(ri)).distinct.sorted
      val cols = cells.map(_.key(ci)).distinct.sorted
      val look = cells.map(c => (c.key(ri), c.key(ci)) -> c.verdict).toMap

      def pad(s : String, n : Int) = s + " " * math.max(0, n - s.length)

      val header = pad("", 7) + cols.map(c => pad(s"$colAxis=$c", 6)).mkString
      val body = rows.map { r =>
        pad(s"$rowAxis=$r", 7) +
          cols.map(c => pad(look.getOrElse((r, c), "?"), 6)).mkString
      }
      (header +: body).mkString("\n")
    }

    /** Roznica wzgledem baseline'u. Pusta lista == regresja przeszla. */
    def diff(base : Grid) : Seq[String] = {
      import Ordering.Implicits.seqOrdering
      val mine   = byKey
      val theirs = base.byKey
      (mine.keySet ++ theirs.keySet).toSeq.sorted.flatMap { k =>
        (theirs.get(k), mine.get(k)) match {
          case (Some(a), Some(b)) if a != b => Some(s"${fmt(k)}: $a -> $b")
          case (Some(a), None)              => Some(s"${fmt(k)}: $a -> BRAK POMIARU")
          case (None,    Some(b))           => Some(s"${fmt(k)}: NOWA KOMORKA -> $b")
          case _                            => None
        }
      }
    }

    private def fmt(k : Seq[Int]) : String =
      axes.zip(k).map { case (a, v) => s"$a=$v" }.mkString(" ")

    /** Scalenie shardow. Przy kolizji klucza wygrywa `other` - zalozenie
      * jest takie, ze shardy sa rozlaczne, wiec kolizja to blad podzialu. */
    def merge(other : Grid) : Grid = {
      require(other.axes == axes, s"rozne osie: $axes vs ${other.axes}")
      val collide = cells.map(_.key).toSet intersect other.cells.map(_.key).toSet
      if (collide.nonEmpty)
        println(s"UWAGA: shardy nachodza sie na ${collide.size} komorkach")
      Grid(name, cells.filterNot(c => other.cells.exists(_.key == c.key)) ++ other.cells)
    }
  }

  object Grid {
    def fromCsv(name : String, path : String) : Grid = {
      val src   = Source.fromFile(path)
      val lines = try src.getLines().filter(_.trim.nonEmpty).toSeq finally src.close()
      require(lines.size >= 2, s"$path: brak danych")

      val header = lines.head.split(",").map(_.trim).toSeq
      require(header.last == "verdict", s"$path: ostatnia kolumna musi byc 'verdict'")
      val axes = header.init

      Grid(name, lines.tail.map { line =>
        val f = line.split(",").map(_.trim).toSeq
        require(f.size == header.size, s"$path: zla liczba kolumn w '$line'")
        Cell(axes.zip(f.init.map(_.toInt)), f.last)
      })
    }
  }
}
