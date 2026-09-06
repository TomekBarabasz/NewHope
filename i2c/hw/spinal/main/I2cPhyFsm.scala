package org.newhope.i2c

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.io.{ReadableOpenDrain, InOutWrapper}


case class I2cPhyFsm(g : I2cGenerics) extends I2cPhyBase(g) { 
  // Clock stretching: slave moze przytrzymac SCL nisko mimo ze my
  // puscilismy linie. Nie liczymy czasu dopoki tego nie zobaczymy.
  def waitSclHigh() : Unit = when(!filter.scl) { timer.restart() }

  val fsm = new StateMachine {
    val sIdle = new State with EntryPoint
    val sStartReleaseSda, sStartReleaseScl, sStartFallSda, sStartFallScl = new State
    val sBitSetup, sBitHighA, sBitHighB, sBitLow                         = new State
    val sStopFallSda, sStopReleaseScl, sStopReleaseSda                   = new State

    sIdle.whenIsActive {
      when(io.cmd.valid) {
        timer.restart()
        switch(io.cmd.mode) {
          is(I2cPhyCmdMode.START) { goto(sStartReleaseSda) }
          is(I2cPhyCmdMode.BIT)   { goto(sBitSetup)        }
          is(I2cPhyCmdMode.STOP)  { goto(sStopFallSda)     }
        }
      }
    }
    // --- START -------------------------------------------------------
    // Ta sama sekwencja obsluguje start "na zimno" i RESTART: najpierw
    // doprowadzamy magistrale do stanu jalowego, potem robimy zbocze.
    sStartReleaseSda.whenIsActive {
      sdaReg := True
      when(timer.done) { timer.restart(); goto(sStartReleaseScl) }
    }
    sStartReleaseScl.whenIsActive {
      sclReg := True
      waitSclHigh()
      when(timer.done) { timer.restart(); goto(sStartFallSda) }
    }
    sStartFallSda.whenIsActive {              // SDA opada przy SCL wysokim
      sdaReg := False
      when(timer.done) { timer.restart(); goto(sStartFallScl) }
    }
    sStartFallScl.whenIsActive {
      sclReg := False
      when(timer.done) { io.cmd.ready := True; goto(sIdle) }
    }

    // --- BIT ---------------------------------------------------------
    // 4 cwiartki: [SCL nisko: setup SDA][SCL wysoko x2][SCL nisko]
    sBitSetup.whenIsActive {                  // wchodzimy przy SCL niskim
      sdaReg := io.cmd.data
      when(timer.done) { timer.restart(); goto(sBitHighA) }
    }
    sBitHighA.whenIsActive {
      sclReg := True
      waitSclHigh()
      when(timer.done) {                      // srodek stanu wysokiego
        io.rsp.valid := True                  // <- tu probkujemy SDA
        timer.restart()
        goto(sBitHighB)
      }
    }
    sBitHighB.whenIsActive {
      when(timer.done) { sclReg := False; timer.restart(); goto(sBitLow) }
    }
    sBitLow.whenIsActive {
      when(timer.done) { io.cmd.ready := True; goto(sIdle) }
    }

    // --- STOP --------------------------------------------------------
    sStopFallSda.whenIsActive {               // wchodzimy przy SCL niskim
      sdaReg := False
      when(timer.done) { timer.restart(); goto(sStopReleaseScl) }
    }
    sStopReleaseScl.whenIsActive {
      sclReg := True
      waitSclHigh()
      when(timer.done) { timer.restart(); goto(sStopReleaseSda) }
    }
    sStopReleaseSda.whenIsActive {            // SDA rosnie przy SCL wysokim
      sdaReg := True
      when(timer.done) { io.cmd.ready := True; goto(sIdle) }
    }
  }
}

// =====================================================================
//  Generacja RTL. InOutWrapper zamienia ReadableOpenDrain na prawdziwe
//  porty inout - bez tego dostaniesz osobne write/read i synteza nie
//  zobaczy dwukierunkowej magistrali.
// =====================================================================
object I2cPhyFsmVerilog extends App {
  SpinalConfig(
    targetDirectory             = "hw/gen/verilog",
    defaultClockDomainFrequency = FixedFrequency(100 MHz),
    anonymSignalUniqueness      = true
  ).generateVerilog(
    InOutWrapper(I2cPhyFsm(I2cGenerics(clkFrequency = 100 MHz)))
  )
}