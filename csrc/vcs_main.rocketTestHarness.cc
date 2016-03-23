// See LICENSE for license details.

#include "htif_emulator.h"
#include "mm.h"
#include "mm_dramsim2.h"
#ifdef VERILATOR
#include "verilated.h"
#include "VrocketTestHarness.h"
#else
#include <DirectC.h>
#endif
#include <stdio.h>
#include <stdlib.h>
#include <vector>
#include <sstream>
#include <iterator>
#include <memory>

#ifdef VERILATOR
typedef unsigned char       vsim_bit_i;
typedef unsigned char *     vsim_bit_o;
typedef const svBitVecVal * vsim_vec_i;
typedef svBitVecVal *       vsim_vec_o;

int vc_getScalar(vsim_vec_i i) { return i[0]; }
int vc_getScalar(vsim_bit_i i) { return i; }

void vc_putScalar(vsim_bit_o o, int v) { o[0] = v; }
void vc_putScalar(vsim_vec_o o, int v) { o[0] = v; }

struct vec32 {
  unsigned int c;
  unsigned int d;
};
struct vec32* vc_4stVectorRef(vsim_vec_i i)
{ return (struct vec32 *)i; }

void vc_put4stVector(vsim_vec_o o, vec32* v)
{ o[0] = v->d; o[1] = v->c; }
#else
typedef vc_handle vsim_bit_i;
typedef vc_handle vsim_bit_o;
typedef vc_handle vsim_vec_i;
typedef vc_handle vsim_vec_o;
#endif

extern "C" {

extern int vcs_main(int argc, char** argv);

static htif_emulator_t* htif;
static unsigned htif_bytes = HTIF_WIDTH / 8;
static mm_t* mm[N_MEM_CHANNELS];
static const char* loadmem;
static bool dramsim = false;
static int memory_channel_mux_select = 0;

void htif_fini(vsim_vec_i failure)
{
  delete htif;
  htif = NULL;
  exit(vc_getScalar(failure));
}

int main(int argc, char** argv)
{
  for (int i = 1; i < argc; i++)
  {
    if (!strcmp(argv[i], "+dramsim"))
      dramsim = true;
    else if (!strncmp(argv[i], "+loadmem=", 9))
      loadmem = argv[i]+9;
    else if (!strncmp(argv[i], "+memory_channel_mux_select=", 27))
      memory_channel_mux_select = atoi(argv[i]+27);
  }

  htif = new htif_emulator_t(std::vector<std::string>(argv + 1, argv + argc));

  for (int i=0; i<N_MEM_CHANNELS; i++) {
    mm[i] = dramsim ? (mm_t*)(new mm_dramsim2_t) : (mm_t*)(new mm_magic_t);
    mm[i]->init(MEM_SIZE / N_MEM_CHANNELS, MEM_DATA_BITS / 8, CACHE_BLOCK_BYTES);
  }

  if (loadmem) {
    void *mems[N_MEM_CHANNELS];
    for (int i = 0; i < N_MEM_CHANNELS; i++)
      mems[i] = mm[i]->get_data();
    load_mem(mems, loadmem, CACHE_BLOCK_BYTES, N_MEM_CHANNELS);
  }

#ifdef VERILATOR
  Verilated::commandArgs(argc, argv);
  {
    auto dut = new VrocketTestHarness;
    while (!Verilated::gotFinish())
      dut->eval();
    delete dut;
    exit(0);
  }
#else
  vcs_main(argc, argv);
  abort(); // should never get here
#endif
}

void memory_tick(
  vsim_vec_i channel,

  vsim_bit_i ar_valid,
  vsim_bit_o ar_ready,
  vsim_vec_i ar_addr,
  vsim_vec_i ar_id,
  vsim_vec_i ar_size,
  vsim_vec_i ar_len,

  vsim_bit_i aw_valid,
  vsim_bit_o aw_ready,
  vsim_vec_i aw_addr,
  vsim_vec_i aw_id,
  vsim_vec_i aw_size,
  vsim_vec_i aw_len,

  vsim_bit_i w_valid,
  vsim_bit_o w_ready,
  vsim_vec_i w_strb,
  vsim_vec_i w_data,
  vsim_bit_i w_last,

  vsim_bit_o r_valid,
  vsim_bit_i r_ready,
  vsim_vec_o r_resp,
  vsim_vec_o r_id,
  vsim_vec_o r_data,
  vsim_bit_o r_last,

  vsim_bit_o b_valid,
  vsim_bit_i b_ready,
  vsim_vec_o b_resp,
  vsim_vec_o b_id)
{
  int c = vc_4stVectorRef(channel)->d;
  assert(c < N_MEM_CHANNELS);
  mm_t* mmc = mm[c];

  uint32_t write_data[mmc->get_word_size()/sizeof(uint32_t)];
  for (size_t i = 0; i < mmc->get_word_size()/sizeof(uint32_t); i++)
    write_data[i] = vc_4stVectorRef(w_data)[i].d;

  uint32_t aw_id_val, ar_id_val;

  if (MEM_ID_BITS == 1) {
    aw_id_val = vc_getScalar(aw_id);
    ar_id_val = vc_getScalar(ar_id);
  } else {
    aw_id_val = vc_4stVectorRef(aw_id)->d;
    ar_id_val = vc_4stVectorRef(ar_id)->d;
  }

  mmc->tick
  (
    vc_getScalar(ar_valid),
    vc_4stVectorRef(ar_addr)->d - MEM_BASE,
    ar_id_val,
    vc_4stVectorRef(ar_size)->d,
    vc_4stVectorRef(ar_len)->d,

    vc_getScalar(aw_valid),
    vc_4stVectorRef(aw_addr)->d - MEM_BASE,
    aw_id_val,
    vc_4stVectorRef(aw_size)->d,
    vc_4stVectorRef(aw_len)->d,

    vc_getScalar(w_valid),
    vc_4stVectorRef(w_strb)->d,
    write_data,
    vc_getScalar(w_last),

    vc_getScalar(r_ready),
    vc_getScalar(b_ready)
  );

  vc_putScalar(ar_ready, mmc->ar_ready());
  vc_putScalar(aw_ready, mmc->aw_ready());
  vc_putScalar(w_ready, mmc->w_ready());
  vc_putScalar(b_valid, mmc->b_valid());
  vc_putScalar(r_valid, mmc->r_valid());
  vc_putScalar(r_last, mmc->r_last());

  vec32 d[mmc->get_word_size()/sizeof(uint32_t)];

  d[0].c = 0;
  d[0].d = mmc->b_resp();
  vc_put4stVector(b_resp, d);

  d[0].c = 0;
  d[0].d = mmc->r_resp();
  vc_put4stVector(r_resp, d);

  if (MEM_ID_BITS > 1) {
    d[0].c = 0;
    d[0].d = mmc->b_id();
    vc_put4stVector(b_id, d);

    d[0].c = 0;
    d[0].d = mmc->r_id();
    vc_put4stVector(r_id, d);
  } else {
    vc_putScalar(b_id, mmc->b_id());
    vc_putScalar(r_id, mmc->r_id());
  }

  for (size_t i = 0; i < mmc->get_word_size()/sizeof(uint32_t); i++)
  {
    d[i].c = 0;
    d[i].d = ((uint32_t*)mmc->r_data())[i];
  }
  vc_put4stVector(r_data, d);
}

void htif_tick
(
  vsim_bit_o htif_in_valid,
  vsim_bit_i htif_in_ready,
  vsim_vec_o htif_in_bits,
  vsim_bit_i htif_out_valid,
  vsim_bit_o htif_out_ready,
  vsim_vec_i htif_out_bits,
  vsim_vec_o exit
)
{
  static bool peek_in_valid;
  static uint32_t peek_in_bits;
  if (vc_getScalar(htif_in_ready))
    peek_in_valid = htif->recv_nonblocking(&peek_in_bits, htif_bytes);

  vc_putScalar(htif_out_ready, 1);
  if (vc_getScalar(htif_out_valid))
  {
    vec32* bits = vc_4stVectorRef(htif_out_bits);
    htif->send(&bits->d, htif_bytes);
  }

  vec32 bits = {0, 0};
  bits.d = peek_in_bits;
  vc_put4stVector(htif_in_bits, &bits);
  vc_putScalar(htif_in_valid, peek_in_valid);

  bits.d = htif->done() ? (htif->exit_code() << 1 | 1) : 0;
  vc_put4stVector(exit, &bits);
}

}
