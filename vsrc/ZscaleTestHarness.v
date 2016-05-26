// See LICENSE for license details.
//

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

module ZscaleTestHarness;

  reg clk   = 0;
  reg reset = 1;
  reg r_reset;

  always #`CLOCK_PERIOD clk = ~clk;

  reg htif_out_ready;
  wire htif_in_valid;
  wire [`HTIF_WIDTH-1:0] htif_in_bits;
  wire htif_in_ready, htif_out_valid;
  wire [`HTIF_WIDTH-1:0] htif_out_bits;

  wire htif_clk;
  wire htif_in_valid_delay;
  wire htif_in_ready_delay;
  wire [`HTIF_WIDTH-1:0] htif_in_bits_delay;

  wire htif_out_valid_delay;
  wire htif_out_ready_delay;
  wire [`HTIF_WIDTH-1:0] htif_out_bits_delay;

  assign #0.1 htif_in_valid_delay = htif_in_valid;
  assign #0.1 htif_in_ready = htif_in_ready_delay;
  assign #0.1 htif_in_bits_delay = htif_in_bits;

  assign #0.1 htif_out_valid = htif_out_valid_delay;
  assign #0.1 htif_out_ready_delay = htif_out_ready;
  assign #0.1 htif_out_bits = htif_out_bits_delay;

  ZscaleTop dut
  (
    .clk(clk),
    .reset(reset),

    .io_prci_reset(reset),
    .io_prci_id(1'd0),
    .io_prci_interrupts_mtip(1'b0),
    .io_prci_interrupts_msip(1'b0),
    .io_prci_interrupts_meip(1'b0),
    .io_prci_interrupts_seip(1'b0),
    .io_prci_interrupts_debug(1'b0),

    .io_host_clk(/*FIXME: htif_clk*/),
    .io_host_clk_edge(),
    .io_host_in_valid(htif_in_valid_delay),
    .io_host_in_ready(htif_in_ready_delay),
    .io_host_in_bits(htif_in_bits_delay),
    .io_host_out_valid(htif_out_valid_delay),
    .io_host_out_ready(htif_out_ready_delay),
    .io_host_out_bits(htif_out_bits_delay)
  );

  reg [1023:0] loadmem = 0;
  reg [1023:0] prog = 0;
  reg [1023:0] vcdplusfile = 0;
  reg [  63:0] max_cycles = 0;
  reg [  63:0] trace_count = 0;
  reg          verbose = 0;
  wire         printf_cond = verbose && !reset;
  integer      stderr = 32'h80000002;
  integer      i;
  reg [127:0]  image [8191:0];
  reg [7:0]  dmem [65535:0];
  reg [31:0] tohost;


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

  assign htif_clk = clk;

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
        htif_in_valid_premux,
        htif_in_ready_premux,
        htif_in_bits_premux,
        htif_out_valid,
        htif_out_ready,
        htif_out_bits,
        exit
      );
    end
  end

  //-----------------------------------------------
  // Start the simulation
  initial
  begin
    $value$plusargs("max-cycles=%d", max_cycles);
    verbose = $test$plusargs("verbose");
    if ($value$plusargs("loadmem=%s", loadmem))
    begin
      $readmemh(loadmem, image);
    end
    if ($value$plusargs("vcdplusfile=%s", vcdplusfile))
    begin
      $vcdplusfile(vcdplusfile);
      $vcdpluson(0);
      $vcdplusmemon(0);
    end

    if ($value$plusargs("prog=%s", prog))
    begin
      $readmemh(prog, dmem);
    end
    
    #0.5;
/*
    // TODO: use C++ to load the program
    for (i=0; i<65536/4; i=i+4) begin
      dut.dram.ram.ram[i/4] = {
        dmem[i + 3],
        dmem[i + 2],
        dmem[i + 1],
	dmem[i + 0]};
    end
*/
    #777.7 reset = 0;
  end

  reg [255:0] reason = 0;
  always @(posedge clk)
  begin
    trace_count = trace_count + 1;

    if (max_cycles > 0 && trace_count > max_cycles)
      reason = "timeout";

    if (!reset)
    begin
      tohost = dut.dram.ram.ram['h1000/4];
      if (tohost & 1)
        $sformat(reason, "tohost = %d", tohost >> 1);

      if (tohost == 'd1)
      begin
        $vcdplusclose;
        $finish;
      end
    end

    if (reason)
    begin
      $fdisplay(stderr, "*** FAILED *** (%s) after %d simulation cycles", reason, trace_count);
      $vcdplusclose;
      $finish;
    end
  end
endmodule
