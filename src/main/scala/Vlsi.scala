// See LICENSE for license details.

package rocketchip

import Chisel._
import cde.Parameters
import junctions._
import uncore._

class MemDessert(topParams: Parameters) extends Module {
  implicit val p = topParams
  val memWidth = p(BackupMemoryWidth)
  val io = new MemDesserIO(memWidth)

  val desser = Module(new AtosDesser(memWidth))
  val conv1 = Module(new AtosManagerConverter)
  val conv2 = Module(new MemIONastiIOConverter(p(CacheBlockOffsetBits)))
  desser.io.narrow <> io.narrow
  conv1.io.atos <> desser.io.wide
  conv2.io.nasti <> conv1.io.nasti
  io.wide <> conv2.io.mem
}

object VLSIUtils {
  def padOutBackupMemWithDividedClock(
      parent: AtosSerializedIO, scr: SCRIO, child: AtosSerializedIO, w: Int) {
    val hio = Module((new SlowIO(512)) { Bits(width = w) })
    hio.io.set_divisor.valid := scr.wen && (scr.waddr === UInt(62))
    hio.io.set_divisor.bits := scr.wdata
    scr.rdata(62) := hio.io.divisor
    scr.allocate(62, "BACKUP_MEM_CLOCK_DIVISOR")

    hio.io.out_fast <> parent.req
    child.req <> hio.io.out_slow
    hio.io.in_slow <> child.resp
    parent.resp <> hio.io.in_fast

    child.clk := hio.io.clk_slow
    child.clk_edge := Reg(next=child.clk && !Reg(next = child.clk))
  }

  def padOutHTIFWithDividedClock(
      htif: HostIO,
      scr: SCRIO,
      host: HostIO,
      htifW: Int) {
    val hio = Module((new SlowIO(512)) { Bits(width = htifW) })
    hio.io.set_divisor.valid := scr.wen && (scr.waddr === UInt(63))
    hio.io.set_divisor.bits := scr.wdata
    scr.rdata(63) := hio.io.divisor
    scr.allocate(63, "HTIF_IO_CLOCK_DIVISOR")

    hio.io.out_fast <> htif.out
    host.out <> hio.io.out_slow
    hio.io.in_slow <> host.in
    htif.in <> hio.io.in_fast

    host.clk := hio.io.clk_slow
    host.clk_edge := Reg(next=host.clk && !Reg(next=host.clk))
  }
}
