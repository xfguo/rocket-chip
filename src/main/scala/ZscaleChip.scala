// See LICENSE for license details.

package rocketchip

import Chisel._
import cde.{Parameters, Field}
import junctions._
import uncore._
import rocket._
import zscale._

case object UseZscale extends Field[Boolean]
case object BuildZscale extends Field[(Bool, Parameters) => Zscale]
case object BootROMCapacity extends Field[Int]
case object DRAMCapacity extends Field[Int]

object ZscaleTopUtils {
  def printSystemInfo()(implicit p: Parameters) = {
    val addrHashMap = p(GlobalAddrHashMap)
    println("Generated Address Map")
    for ((name, base, region) <- addrHashMap.sortedEntries) {
      println(f"\t$name%s $base%x - ${base + region.size - 1}%x")
    }
    println("Generated Configuration String")
    println(new String(p(ConfigString)))
  }
  def makeBootROM()(implicit p: Parameters) = {
    val rom = java.nio.ByteBuffer.allocate(32)
    rom.order(java.nio.ByteOrder.LITTLE_ENDIAN)

    // for now, have the reset vector jump straight to memory
    val addrHashMap = p(GlobalAddrHashMap)
    val resetToMemDist = addrHashMap("mem").start
    require(resetToMemDist.toInt == (resetToMemDist.toInt >> 12 << 12))
    // TODO: zscale reset vector is 0x200
    val configStringAddr = Integer.parseInt("1000", 16) + rom.capacity

    rom.putInt(0x000002b7 + resetToMemDist.toInt) // lui t0, &mem
    rom.putInt(0x00028067)                        // jr t0
    rom.putInt(0)                                 // reserved
    rom.putInt(configStringAddr)                  // pointer to config string
    rom.putInt(0)                                 // default trap vector
    rom.putInt(0)                                 //   ...
    rom.putInt(0)                                 //   ...
    rom.putInt(0)                                 //   ...

    rom.array() ++ p(ConfigString).toSeq
  }
}

class ZscaleSystem(implicit p: Parameters)  extends Module {
  val io = new Bundle {
    val host = new HostIO(p(HtifKey).width)
    val jtag = new HastiMasterIO().flip
    val bootmem = new HastiSlaveIO().flip
    val dram = new HastiSlaveIO().flip
    val spi = new HastiSlaveIO().flip
    val led = new PociIO
    val corereset = new PociIO
  }


  val htif = Module(new HtifZ(CSRs.mreset))
  val htif_cpu_reset = Reg(next=Reg(next=htif.io.cpu(0).reset))
  
  val core = p(BuildZscale)(htif_cpu_reset, p)
  
  io.host <> htif.io.host
  
  val scrFile = Module(new SCRFile("ZSCALE_SCR", 0))
  scrFile.io.smi <> htif.io.scr

  val bootmem_afn = (addr: UInt) => addr(31, 14) === UInt(0)

  val sbus_afn = (addr: UInt) => addr(31, 29).orR
  val dram_afn = (addr: UInt) => addr(31, 26) === UInt(32) // 0x80000000
  val spi_afn = (addr: UInt) => addr(31, 26) === UInt(9) && addr(25, 14) === UInt(0)

  val pbus_afn = (addr: UInt) => addr(31) === UInt(1)
  val led_afn = (addr: UInt) => addr(31) === UInt(1) && addr(30, 10) === UInt(0)
  val corereset_afn = (addr: UInt) => addr(31) === UInt(1) && addr(30, 10) === UInt(1) /* TODO: do we need this? */

  val xbar = Module(new HastiXbar(3, Seq(bootmem_afn, sbus_afn)))
  val sadapter = Module(new HastiSlaveToMaster)
  val sbus = Module(new HastiBus(Seq(dram_afn, spi_afn, pbus_afn)))
  val padapter = Module(new HastiToPociBridge)
  val pbus = Module(new PociBus(Seq(led_afn, corereset_afn)))

  /* TODO: just follow rocketChip */
  core.io.prci.id := UInt(0)
  core.io.prci.interrupts.mtip := Bool(false)  /* TODO */
  core.io.prci.interrupts.meip := Bool(false)  /* TODO */
  core.io.prci.interrupts.seip := Bool(false)  /* TODO */
  core.io.prci.interrupts.debug := Bool(false)  /* TODO */
  core.io.prci.reset := htif_cpu_reset // FIXME
  
  xbar.io.masters(0) <> htif.io.mem
  xbar.io.masters(1) <> core.io.dmem
  xbar.io.masters(2) <> core.io.imem

  io.bootmem <> xbar.io.slaves(0)
  sadapter.io.in <> xbar.io.slaves(1)

  sbus.io.master <> sadapter.io.out
  io.dram <> sbus.io.slaves(0)
  io.spi <> sbus.io.slaves(1)
  padapter.io.in <> sbus.io.slaves(2)

  pbus.io.master <> padapter.io.out
  io.led <> pbus.io.slaves(0)
  io.corereset <> pbus.io.slaves(1)

  ZscaleTopUtils.printSystemInfo
}

class ZscaleTop(topParams: Parameters) extends Module {
  implicit val p = topParams.alterPartial({case TLId => "L1toL2" })
  val io = new Bundle {
    val host = new HostIO(p(HtifKey).width)
  }

  val sys = Module(new ZscaleSystem)
  val bootmem = Module(new HastiROM(ZscaleTopUtils.makeBootROM()))
  val dram = Module(new HastiRAM(p(DRAMCapacity)/4))

  sys.io.host <> io.host
  bootmem.io <> sys.io.bootmem
  dram.io <> sys.io.dram
  
}
