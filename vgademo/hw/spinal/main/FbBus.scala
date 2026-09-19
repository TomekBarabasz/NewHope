package newhope.vgademo

import spinal.core._
import spinal.lib._
import newhope.mcb.{MigInstr, MigPort}

/**
 * Komenda wewnetrzna. Celowo UBOZSZA od MigCmd: nie ma pola instr, tylko flage
 * write, bo zaden z trzech masterow nie potrzebuje auto-precharge ani refresh.
 * `bl` zachowuje semantyke MCB (dlugosc MINUS JEDEN), zeby nie robic dwoch
 * konwencji w jednym projekcie.
 */
case class FbCmd(c: VgaFbConfig) extends Bundle {
  val write = Bool()
  val addr  = UInt(c.mig.addrWidth bits)
  val bl    = UInt(6 bits)
}

/**
 * Magistrala miedzy masterami obrazu a MCB.
 *
 * Po co osobny bundle, skoro MigPort juz istnieje: zeby dokladnie jedno miejsce
 * w projekcie znalo nazwy pol MigCmd/MigWrData (FbBus.toMigPort ponizej).
 * Jesli warstwa MCB sie przemianuje, zmienia sie kilkanascie linii w jednym
 * pliku, a nie trzy moduly.
 */
case class FbBus(c: VgaFbConfig) extends Bundle with IMasterSlave {
  val cmd = Stream(FbCmd(c))
  val wr  = Stream(Bits(c.mig.dataWidth bits))
  val rd  = Stream(Bits(c.mig.dataWidth bits))

  override def asMaster(): Unit = {
    master(cmd, wr)
    slave(rd)
  }

  /** Master, ktory nie pisze / nie czyta, wywoluje to na nieuzywanych kanalach. */
  def idleWrite(): Unit = {
    wr.valid := False
    wr.payload := B(0, c.mig.dataWidth bits)
  }
  def idleRead(): Unit = {
    rd.ready := False
  }
}

object FbBus {

  /**
   * JEDYNE miejsce w tym projekcie, ktore zna nazwy pol MigPort.
   *
   * Maska zapisu jest aktywna WYSOKIM (bit = 1 to bajt pominiety), wiec zero
   * oznacza "zapisz wszystko". Nic tu nie maskujemy, bo framebuffer zawsze
   * jedzie pelnymi slowami.
   */
  def toMigPort(bus: FbBus): MigPort = {
    val c = bus.c.mig
    val p = MigPort(c)

    p.cmd.valid         := bus.cmd.valid
    bus.cmd.ready       := p.cmd.ready
    p.cmd.payload.instr := bus.cmd.payload.write ? MigInstr.WRITE | MigInstr.READ
    p.cmd.payload.bl    := bus.cmd.payload.bl
    p.cmd.payload.addr  := bus.cmd.payload.addr.resized

    p.wr.valid        := bus.wr.valid
    bus.wr.ready      := p.wr.ready
    p.wr.payload.data := bus.wr.payload
    p.wr.payload.mask := B(0, c.maskWidth bits)

    bus.rd.valid   := p.rd.valid
    bus.rd.payload := p.rd.payload
    p.rd.ready     := bus.rd.ready

    p
  }
}

/**
 * Arbiter trzech masterow na jeden port MCB.
 *
 * Port 128-bitowy to Config-5, czyli JEDEN port dwukierunkowy - nie ma opcji
 * "dam czytaniu wlasny port". Zreszta wiecej portow i tak nie dodaje
 * przepustowosci (§6 README kontrolera), tylko narzut arbitra.
 *
 * Priorytet: odswiezanie obrazu > malowanie wzorca > host. Odswiezanie zajmuje
 * ~1,2% portu i ma twardy deadline, host nie ma zadnego.
 *
 * Bezpieczenstwo kolejnosci: na jednym porcie dane odczytu wracaja w kolejnosci
 * komend i bez tagu. Czyta TYLKO `read`, wiec nie ma komu pomylic wlasciciela
 * danych. Komendy zapisu moga sie wcinac miedzy odczyty, bo kazdy beat Streama
 * jest atomowy, a dane zapisu leza juz w FIFO zanim pojawi sie ich komenda.
 *
 * Kanal `wr` wymaga silniejszego zalozenia: dane w FIFO nalezacego do MCB nie
 * maja tagu, wiec dwoch piszacych NIE MOZE nadawac naraz. Dlatego painter i host
 * wykluczaja sie konstrukcyjnie (host.enable = !painter.busy), a arbiter
 * dodatkowo trzyma priorytet paintera.
 */
case class FbArbiter(c: VgaFbConfig) extends Component {
  val io = new Bundle {
    val read  = slave(FbBus(c))
    val paint = slave(FbBus(c))
    val host  = slave(FbBus(c))
    val mem   = master(FbBus(c))
  }

  // ---- komendy: priorytet stały ---------------------------------------
  val grantRead  = io.read.cmd.valid
  val grantPaint = !grantRead && io.paint.cmd.valid
  val grantHost  = !grantRead && !grantPaint && io.host.cmd.valid

  io.mem.cmd.valid := io.read.cmd.valid || io.paint.cmd.valid || io.host.cmd.valid
  io.mem.cmd.payload := grantRead ? io.read.cmd.payload |
                       (grantPaint ? io.paint.cmd.payload | io.host.cmd.payload)

  io.read.cmd.ready  := grantRead && io.mem.cmd.ready
  io.paint.cmd.ready := grantPaint && io.mem.cmd.ready
  io.host.cmd.ready  := grantHost && io.mem.cmd.ready

  // ---- dane zapisu: tylko painter i host -------------------------------
  val wrPaint = io.paint.wr.valid

  io.mem.wr.valid   := io.paint.wr.valid || io.host.wr.valid
  io.mem.wr.payload := wrPaint ? io.paint.wr.payload | io.host.wr.payload

  io.paint.wr.ready := wrPaint && io.mem.wr.ready
  io.host.wr.ready  := !wrPaint && io.mem.wr.ready

  io.read.wr.ready  := False   // czytelnik nie pisze

  // ---- dane odczytu: tylko czytelnik ------------------------------------
  io.read.rd.valid   := io.mem.rd.valid
  io.read.rd.payload := io.mem.rd.payload
  io.mem.rd.ready    := io.read.rd.ready

  io.paint.rd.valid   := False
  io.paint.rd.payload := B(0, c.mig.dataWidth bits)
  io.host.rd.valid    := False
  io.host.rd.payload  := B(0, c.mig.dataWidth bits)
}
