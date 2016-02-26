// See LICENSE for license details.

package rocketchip

import Chisel._
import cde.Parameters
import junctions._
import uncore._

class BackupMemory(topParams: Parameters) extends Module {
  implicit val p = topParams
  val memWidth = p(BackupMemoryWidth)
  val memDepth = p(BackupMemoryDepth)
  val io = new AtosSerializedIO(memWidth).flip

  val desser = Module(new AtosDesser(memWidth))
  val conv = Module(new AtosManagerConverter)
  val mem = Module(new NastiRAM(memDepth))
  desser.io.narrow <> io
  conv.io.atos <> desser.io.wide
  mem.io <> conv.io.nasti
}

object VLSIUtils {
  def getClockDivCtrl(scr: SCRIO): ValidIO[UInt] = {
    val set_div = Wire(Valid(UInt(width = 32)))
    val divisor = RegEnable(set_div.bits, set_div.valid)
    scr.rdata(63) := divisor
    scr.allocate(63, "HTIF_IO_CLOCK_DIVISOR")
    set_div.valid := scr.wen && (scr.waddr === UInt(63))
    set_div.bits := scr.wdata
    set_div
  }

  def padOutBackupMemWithDividedClock(
      parent: AtosSerializedIO, child: AtosSerializedIO,
      set_div: ValidIO[UInt], w: Int) {
    val hio = Module((new SlowIO(512)) { Bits(width = w) })
    hio.io.set_divisor := set_div

    hio.io.out_fast <> parent.req
    child.req <> hio.io.out_slow
    hio.io.in_slow <> child.resp
    parent.resp <> hio.io.in_fast

    child.clk := hio.io.clk_slow
    child.clk_edge := Reg(next=child.clk && !Reg(next = child.clk))
  }

  def padOutHTIFWithDividedClock(
      htif: HostIO, host: HostIO,
      set_div: ValidIO[UInt], htifW: Int) {
    val hio = Module((new SlowIO(512)) { Bits(width = htifW) })
    hio.io.set_divisor := set_div

    hio.io.out_fast <> htif.out
    host.out <> hio.io.out_slow
    hio.io.in_slow <> host.in
    htif.in <> hio.io.in_fast

    host.clk := hio.io.clk_slow
    host.clk_edge := Reg(next=host.clk && !Reg(next=host.clk))
  }
}
