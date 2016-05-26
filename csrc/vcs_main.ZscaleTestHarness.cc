// See LICENSE for license details.

#include "htif_emulator.h"
#include <DirectC.h>
#include <stdio.h>

extern "C" {

extern int vcs_main(int argc, char** argv);

static htif_emulator_t* htif;
static unsigned htif_bytes = HTIF_WIDTH / 8;
//static const char* loadmem;

void htif_fini(vc_handle failure)
{
  delete htif;
  htif = NULL;
  exit(vc_getScalar(failure));
}

int main(int argc, char** argv)
{
  htif = new htif_emulator_t(std::vector<std::string>(argv + 1, argv + argc));

  vcs_main(argc, argv);
  abort(); // should never get here
}

void htif_tick
(
  vc_handle htif_in_valid,
  vc_handle htif_in_ready,
  vc_handle htif_in_bits,
  vc_handle htif_out_valid,
  vc_handle htif_out_ready,
  vc_handle htif_out_bits,
  vc_handle exit
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
