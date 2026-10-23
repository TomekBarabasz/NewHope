package newhope.vertebra.hil

import java.io.File
import scala.sys.process._
import scala.util.Try

// =====================================================================
//  Zrodla plytki w gicie, przez granice submodule'i.
//
//  vertebra-hil, lib i projekt to rozne repo: bitstream FE sklada sie z
//  adaptera i rdzenia (vertebra-hil), front_end i com (lib), a calosc
//  spina projekt, ktory przypina oba submodule'e. Zrodlo to katalog
//  (absolutny) z wykluczeniami; repo, w ktorym lezy, i jego sciezke w tym
//  repo HilRepo ustala sam (git -C <katalog>).
//
//  Build (rejestr FPGA, `ver` ESP32) to commit "repo kotwicy": dla FPGA
//  repo najwyzszego poziomu (projekt), dla ESP32 repo, w ktorym lezy
//  esp32/ (tak liczy CMake). Commit zrodla w submodule'u to wskaznik
//  zapisany w commicie kotwicy, wiec zmiane w lib albo vertebra-hil
//  widac tak samo jak zmiane w projekcie.
// =====================================================================

/** Katalog zrodel z wykluczeniami wzgledem niego (np. "hw/spinal/test"). */
case class HilSource(dir : File, excludes : Seq[String] = Nil)

object HilSource {
  /** Wykluczenia katalogu projektu sbt: testy, wygenerowany RTL (Verilog i
    * .bin w hw/gen sa wynikami budowania), artefakty. */
  val projectExcludes : Seq[String] = Seq("hw/spinal/test", "hw/gen", "src/test", "target", "simWorkspace")

  /** Katalogi projektow ze zmiennej (HilBuild: VERTEBRA_HIL_FPGA_SRC). */
  def fromEnv(variable : String, env : Map[String, String] = sys.env) : Seq[HilSource] =
    env.get(variable).toSeq
      .flatMap(_.split(java.util.regex.Pattern.quote(File.pathSeparator)))
      .map(_.trim).filter(_.nonEmpty)
      .map(d => HilSource(new File(d), projectExcludes))
}

object HilRepo {
  private val quiet = ProcessLogger(_ => (), _ => ())

  /** Wyjscie gita bez \r: na Windows linie koncza sie \r\n. */
  def git(dir : File, args : String*) : Option[String] =
    Try(Process("git" +: "-C" +: dir.toString +: args).!!(quiet).replace("\r", "").trim).toOption

  private def canon(f : File) : File = Try(f.getCanonicalFile).getOrElse(f.getAbsoluteFile)

  /** Korzen vertebra-hil: VERTEBRA_HIL_ROOT (HilBuild), inaczej w gore od cwd do vertebra-hil.md. */
  def root(env : Map[String, String] = sys.env) : Option[File] =
    env.get("VERTEBRA_HIL_ROOT").filter(_.nonEmpty).map(d => canon(new File(d))).orElse {
      Iterator.iterate(canon(new File(".")))(_.getParentFile).takeWhile(_ != null)
        .find(d => new File(d, "vertebra-hil.md").isFile)
    }

  /** Repo, w ktorym lezy katalog. */
  def repoOf(dir : File) : Option[File] =
    git(canon(dir), "rev-parse", "--show-toplevel").filter(_.nonEmpty).map(p => canon(new File(p)))

  /** Repo nadrzedne (dla submodule'a), inaczej None. */
  def superOf(repo : File) : Option[File] =
    git(repo, "rev-parse", "--show-superproject-working-tree").filter(_.nonEmpty).map(p => canon(new File(p)))

  /** Repo najwyzszego poziomu: projekt, ktory przypina submodule'e. */
  def topOf(dir : File) : Option[File] =
    repoOf(dir).map(r => Iterator.iterate(Option(r))(_.flatMap(superOf)).takeWhile(_.isDefined).map(_.get).toSeq.last)

  /** Sciezka `inner` wzgledem `outer`, z "/" (git na Windows tez). */
  def rel(outer : File, inner : File) : String =
    canon(outer).toPath.relativize(canon(inner).toPath).toString.replace('\\', '/')

  /** Pathspec zrodla w jego repo. */
  def pathspec(repo : File, s : HilSource) : Seq[String] = {
    val r    = rel(repo, s.dir)
    val base = if (r.isEmpty) "." else r
    base +: s.excludes.map(e => s":(exclude)${if (r.isEmpty) e else s"$r/$e"}")
  }

  /** Commit repo `repo` w rewizji `rev` repo `anchor`: dla anchor samego
    * to rewizja, dla submodule'a (takze zagniezdzonego) wskaznik zapisany
    * w tej rewizji. None, gdy repo nie lezy pod anchor albo wskaznika
    * nie ma (submodule dodany pozniej). */
  def commitIn(repo : File, anchor : File, rev : String) : Option[String] =
    if (canon(repo) == canon(anchor)) git(anchor, "rev-parse", "--verify", "-q", s"$rev^{commit}")
    else superOf(repo).flatMap { parent =>
      commitIn(parent, anchor, rev).flatMap(pc => git(parent, "rev-parse", "--verify", "-q", s"$pc:${rel(parent, repo)}"))
    }

  /** Czy zrodlo ma zmiany wzgledem HEAD kotwicy: niezacommitowane w swoim
    * repo albo submodule przesuniety bez commita w kotwicy. */
  def dirty(s : HilSource, anchor : File) : Boolean =
    repoOf(s.dir) match {
      case None => false
      case Some(repo) =>
        val local = git(repo, Seq("status", "--porcelain", "--") ++ pathspec(repo, s) : _*).exists(_.nonEmpty)
        val moved = canon(repo) != canon(anchor) && git(repo, "rev-parse", "HEAD") != commitIn(repo, anchor, "HEAD")
        local || moved
    }

  /** (8 cyfr hash HEAD kotwicy, dirty) - wartosc rejestru build / `ver`. */
  def buildId(anchor : File, sources : Seq[HilSource]) : Option[(String, Boolean)] =
    git(anchor, "rev-parse", "--short=8", "HEAD").filter(_.length >= 8).map(h => (h.take(8), sources.exists(dirty(_, anchor))))
}
