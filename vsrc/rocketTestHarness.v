// See LICENSE for license details.

`ifdef VERILATOR
import "DPI-C" function void htif_fini(input bit [31:0] failure);

import "DPI-C" function void htif_tick
(
  output bit                    htif_in_valid,
  input  bit                    htif_in_ready,
  output bit  [`HTIF_WIDTH-1:0] htif_in_bits,

  input  bit                    htif_out_valid,
  output bit                    htif_out_ready,
  input  bit  [`HTIF_WIDTH-1:0] htif_out_bits,

  output bit  [31:0]            exit
);

import "DPI-C" function void memory_tick
(
  input  bit [31:0]               channel,

  input  bit                      ar_valid,
  output bit                      ar_ready,
  input  bit [`MEM_ADDR_BITS-1:0] ar_addr,
  input  bit [`MEM_ID_BITS-1:0]   ar_id,
  input  bit [2:0]                ar_size,
  input  bit [7:0]                ar_len,

  input  bit                      aw_valid,
  output bit                      aw_ready,
  input  bit [`MEM_ADDR_BITS-1:0] aw_addr,
  input  bit [`MEM_ID_BITS-1:0]   aw_id,
  input  bit [2:0]                aw_size,
  input  bit [7:0]                aw_len,

  input  bit                      w_valid,
  output bit                      w_ready,
  input  bit [`MEM_STRB_BITS-1:0] w_strb,
  input  bit [`MEM_DATA_BITS-1:0] w_data,
  input  bit                      w_last,

  output bit                      r_valid,
  input  bit                      r_ready,
  output bit [1:0]                r_resp,
  output bit [`MEM_ID_BITS-1:0]   r_id,
  output bit [`MEM_DATA_BITS-1:0] r_data,
  output bit                      r_last,

  output bit                      b_valid,
  input  bit                      b_ready,
  output bit [1:0]                b_resp,
  output bit [`MEM_ID_BITS-1:0]   b_id
);
`else
extern "A" void htif_fini(input reg failure);

extern "A" void htif_tick
(
  output reg                    htif_in_valid,
  input  reg                    htif_in_ready,
  output reg  [`HTIF_WIDTH-1:0] htif_in_bits,

  input  reg                    htif_out_valid,
  output reg                    htif_out_ready,
  input  reg  [`HTIF_WIDTH-1:0] htif_out_bits,

  output reg  [31:0]            exit
);

extern "A" void memory_tick
(
  input  reg [31:0]               channel,

  input  reg                      ar_valid,
  output reg                      ar_ready,
  input  reg [`MEM_ADDR_BITS-1:0] ar_addr,
  input  reg [`MEM_ID_BITS-1:0]   ar_id,
  input  reg [2:0]                ar_size,
  input  reg [7:0]                ar_len,

  input  reg                      aw_valid,
  output reg                      aw_ready,
  input  reg [`MEM_ADDR_BITS-1:0] aw_addr,
  input  reg [`MEM_ID_BITS-1:0]   aw_id,
  input  reg [2:0]                aw_size,
  input  reg [7:0]                aw_len,

  input  reg                      w_valid,
  output reg                      w_ready,
  input  reg [`MEM_STRB_BITS-1:0] w_strb,
  input  reg [`MEM_DATA_BITS-1:0] w_data,
  input  reg                      w_last,

  output reg                      r_valid,
  input  reg                      r_ready,
  output reg [1:0]                r_resp,
  output reg [`MEM_ID_BITS-1:0]   r_id,
  output reg [`MEM_DATA_BITS-1:0] r_data,
  output reg                      r_last,

  output reg                      b_valid,
  input  reg                      b_ready,
  output reg [1:0]                b_resp,
  output reg [`MEM_ID_BITS-1:0]   b_id
);
`endif

module rocketTestHarness;

  reg [31:0] seed;
`ifdef VERILATOR
  initial seed = 0;
`else
  initial seed = $get_initial_random_seed();
`endif

  //-----------------------------------------------
  // Instantiate the processor

  reg clk   = 1'b0;
  reg reset = 1'b1;
  reg r_reset;
  reg start = 1'b0;

  /* verilator lint_off STMTDLY */
  always #`CLOCK_PERIOD clk = ~clk;

  reg [  31:0] n_mem_channel = `N_MEM_CHANNELS;
  reg [  31:0] htif_width = `HTIF_WIDTH;
  reg [  31:0] mem_width = `MEM_DATA_BITS;
  reg [  63:0] max_cycles = 0;
  reg [  63:0] trace_count = 0;
  reg [1023:0] loadmem = 0;
  reg [1023:0] vcdplusfile = 0;
  reg [1023:0] vcdfile = 0;
  reg   [31:0] wide_verbose;
  reg          verbose = 0;
  wire         printf_cond = verbose && !reset;
  integer      stderr = 32'h80000002;

`include `TBVFRAG

  always @(posedge clk)
  begin
    r_reset <= reset;
  end

  reg htif_in_valid_premux;
  reg [`HTIF_WIDTH-1:0] htif_in_bits_premux;
  assign htif_in_bits = htif_in_bits_premux;
  assign htif_in_valid = htif_in_valid_premux;
  wire htif_in_ready_premux = htif_in_ready;
  reg [31:0] exit = 0;

  wire [31:0] dpi_exit;
  wire dpi_htif_out_ready;
  wire dpi_htif_in_ready_premux;
  wire dpi_htif_in_valid_premux;

  always @(posedge htif_clk)
  begin
    if (reset || r_reset)
    begin
      htif_in_valid_premux <= 0;
      htif_out_ready <= 0;
      exit <= 0;
    end
    else
    begin
      htif_tick
      (
        dpi_htif_in_valid_premux,
        dpi_htif_in_ready_premux,
        htif_in_bits_premux,
        htif_out_valid,
        dpi_htif_out_ready,
        htif_out_bits,
        dpi_exit
      );
      exit <= dpi_exit;
      htif_out_ready <= dpi_htif_out_ready;
      htif_in_ready_premux <= dpi_htif_in_ready_premux;
      htif_in_valid_premux <= dpi_htif_in_valid_premux;
    end
  end

  //-----------------------------------------------
  // Start the simulation

  // Read input arguments and initialize
  initial
  begin
    if ($value$plusargs("max-cycles=%d", max_cycles) !== 1) $stop;
`ifdef MEM_BACKUP_EN
    $value$plusargs("loadmem=%s", loadmem);
    if (loadmem)
      $readmemh(loadmem, mem.ram);
`endif
    wide_verbose = $test$plusargs("verbose");
    verbose = wide_verbose[0];
`ifdef DEBUG
    if ($value$plusargs("vcdplusfile=%s", vcdplusfile))
    begin
      $vcdplusfile(vcdplusfile);
      $vcdpluson(0);
      $vcdplusmemon(0);
    end

    if ($value$plusargs("vcdfile=%s", vcdfile))
    begin
      $dumpfile(vcdfile);
      $dumpvars(0, dut);
      $dumpon;
    end
`define VCDPLUSCLOSE $vcdplusclose; $dumpoff;
`else
`define VCDPLUSCLOSE
`endif

    // Strobe reset
    #777.7 reset = 0;

  end

  reg [255:0] reason = 0;
  always @(posedge clk)
  begin
    if (max_cycles > 0 && trace_count > max_cycles)
      reason = "timeout";
    if (exit > 1)
      $sformat(reason, "tohost = %d", exit >> 1);

    if (reason != 256'b0)
    begin
      $fdisplay(stderr, "*** FAILED *** (%s) after %d simulation cycles", reason, trace_count);
      `VCDPLUSCLOSE
      htif_fini(32'b1);
    end

    if (exit == 1)
    begin
      `VCDPLUSCLOSE
      htif_fini(32'b0);
    end
  end

  always @(posedge clk)
  begin
    trace_count = trace_count + 1;
`ifdef GATE_LEVEL
    if (verbose)
    begin
      $fdisplay(stderr, "C: %10d", trace_count-1);
    end
`endif
  end

endmodule
