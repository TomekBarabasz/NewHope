package newhope.core

import spinal.core._

// =====================================================================
//  Konwencje projektu dla generacji i symulacji - jedno miejsce.
//  Uzywaja go testy wszystkich bibliotek (przez SimEnv) i top-levele
//  plytek. Symulacja i synteza musza widziec TEN SAM rodzaj resetu,
//  inaczej test sprawdza inne zachowanie niz to, ktore idzie na plytke.
//
//  Kontrakt numeryczny v1, Konwencje: reset synchroniczny, aktywny
//  wysoko; tablice stale inline, nie przez $readmemh/$readmemb.
//
//  targetDirectory jest wzgledny: run i test sa forkowane z katalogu
//  projektu (build.sbt), wiec "gen" = demo/<plytka>/gen.
//
//  Plytka z innymi potrzebami bierze kopie:
//    Conventions.spinal.copy(defaultClockDomainFrequency = FixedFrequency(41.67 MHz))
//  Wlasne domeny zegarowe (PLL, MCB) plytka tworzy w swoim top-levelu.
// =====================================================================
object Conventions {
  def spinal = SpinalConfig(
    targetDirectory                = "hw/gen",
    defaultConfigForClockDomains   = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH),
    onlyStdLogicVectorAtTopLevelIo = false,
    inlineRom                      = true
  )
}
