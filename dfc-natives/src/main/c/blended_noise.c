#include "dfc_internal.h"
#include <math.h>
#include <stdlib.h>

static double clamped_lerp_mc(double start, double end, double delta) {
  if (delta < 0.0) {
    return start;
  }
  return delta > 1.0 ? end : start + delta * (end - start);
}

static void wrap3_coords(double d0, double d1, double d2, double d11, double *wx, double *wy, double *wz) {
  *wx = dfc_wrap_axis(d0 * d11);
  *wy = dfc_wrap_axis(d1 * d11);
  *wz = dfc_wrap_axis(d2 * d11);
}

void dfc_blended_noise_sample1(const DfcBlendedSpec *s, double bx, double by, double bz, double *out) {
  double d0 = bx * s->xz_multiplier;
  double d1 = by * s->y_multiplier;
  double d2 = bz * s->xz_multiplier;
  double d3 = d0 / s->xz_factor;
  double d4 = d1 / s->y_factor;
  double d5 = d2 / s->xz_factor;
  double d6 = s->y_multiplier * s->smear_scale_multiplier;
  double d7 = d6 / s->y_factor;

  double d10 = 0.0;
  for (int i = 0; i < 8; i++) {
    if (!s->main_present[i]) continue;
    double d11 = 1.0 / (double) (1LL << i);
    double wx, wy, wz;
    wrap3_coords(d3, d4, d5, d11, &wx, &wy, &wz);
    double n = dfc_improved_noise_5(&s->main_octaves[i], wx, wy, wz, d7 * d11, d4 * d11);
    d10 += n / d11;
  }

  double d16 = (d10 / 10.0 + 1.0) * 0.5;
  double d8 = 0.0;
  double d9 = 0.0;

  if (d16 < 1.0) {
    for (int j = 0; j < 16; j++) {
      if (!s->min_present[j]) continue;
      double d11 = 1.0 / (double) (1LL << j);
      double wx, wy, wz;
      wrap3_coords(d0, d1, d2, d11, &wx, &wy, &wz);
      double n = dfc_improved_noise_5(&s->min_octaves[j], wx, wy, wz, d6 * d11, d1 * d11);
      d8 += n / d11;
    }
  }

  if (d16 > 0.0) {
    for (int j = 0; j < 16; j++) {
      if (!s->max_present[j]) continue;
      double d11 = 1.0 / (double) (1LL << j);
      double wx, wy, wz;
      wrap3_coords(d0, d1, d2, d11, &wx, &wy, &wz);
      double n = dfc_improved_noise_5(&s->max_octaves[j], wx, wy, wz, d6 * d11, d1 * d11);
      d9 += n / d11;
    }
  }

  double v = clamped_lerp_mc(d8 / 512.0, d9 / 512.0, d16) / 128.0;
  *out = v;
  (void) s->max_value;
}

void dfc_blended_noise_batch(const DfcBlendedSpec *s, const double *xs, const double *ys, const double *zs,
                             double *outs, int n, int use_avx2) {
  (void) use_avx2;
  for (int i = 0; i < n; i++) {
    dfc_blended_noise_sample1(s, xs[i], ys[i], zs[i], &outs[i]);
  }
}

DfcBlendedSpec *dfc_blended_spec_alloc_heap(const double *doubles6,
                                            const uint8_t *main_perm, const double *main_orig,
                                            const uint8_t *min_perm, const double *min_orig,
                                            const uint8_t *max_perm, const double *max_orig,
                                            const uint8_t *main_pres, const uint8_t *min_pres,
                                            const uint8_t *max_pres) {
  DfcBlendedSpec *b = (DfcBlendedSpec *) calloc(1, sizeof(DfcBlendedSpec));
  if (!b) return NULL;
  b->xz_multiplier = doubles6[0];
  b->y_multiplier = doubles6[1];
  b->xz_factor = doubles6[2];
  b->y_factor = doubles6[3];
  b->smear_scale_multiplier = doubles6[4];
  b->max_value = doubles6[5];

  for (int i = 0; i < 8; i++) {
    b->main_present[i] = main_pres[i];
    if (b->main_present[i]) {
      memcpy(b->main_octaves[i].p, main_perm + (size_t) i * 256, 256);
      b->main_octaves[i].xo = main_orig[(size_t) i * 3];
      b->main_octaves[i].yo = main_orig[(size_t) i * 3 + 1];
      b->main_octaves[i].zo = main_orig[(size_t) i * 3 + 2];
    }
  }
  for (int i = 0; i < 16; i++) {
    b->min_present[i] = min_pres[i];
    if (b->min_present[i]) {
      memcpy(b->min_octaves[i].p, min_perm + (size_t) i * 256, 256);
      b->min_octaves[i].xo = min_orig[(size_t) i * 3];
      b->min_octaves[i].yo = min_orig[(size_t) i * 3 + 1];
      b->min_octaves[i].zo = min_orig[(size_t) i * 3 + 2];
    }
  }
  for (int i = 0; i < 16; i++) {
    b->max_present[i] = max_pres[i];
    if (b->max_present[i]) {
      memcpy(b->max_octaves[i].p, max_perm + (size_t) i * 256, 256);
      b->max_octaves[i].xo = max_orig[(size_t) i * 3];
      b->max_octaves[i].yo = max_orig[(size_t) i * 3 + 1];
      b->max_octaves[i].zo = max_orig[(size_t) i * 3 + 2];
    }
  }
  return b;
}

void dfc_blended_spec_free(DfcBlendedSpec *s) {
  free(s);
}
