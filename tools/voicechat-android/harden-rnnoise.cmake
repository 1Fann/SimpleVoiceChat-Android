# Apply only to the build-local copy of the hash-pinned RNNoise source.
# Fail closed if a future source update changes any of the expected contexts.
function(voicechat_replace_once path before after)
    file(READ "${path}" contents)
    string(FIND "${contents}" "${before}" first)
    string(FIND "${contents}" "${before}" last REVERSE)
    if(first EQUAL -1 OR NOT first EQUAL last)
        message(FATAL_ERROR "RNNoise hardening expected exactly one matching context in ${path}")
    endif()
    string(REPLACE "${before}" "${after}" contents "${contents}")
    file(WRITE "${path}" "${contents}")
endfunction()

# Buffer-backed models do not own a FILE. The pinned upstream constructor left
# this pointer uninitialized, so rnnoise_model_free() could fclose random memory.
voicechat_replace_once("${RNNOISE}/src/denoise.c"
[=[  model->const_blob = ptr;
  model->blob_len = len;
  return model;]=]
[=[  model->const_blob = ptr;
  model->blob_len = len;
  model->file = NULL;
  return model;]=])

# A failed lookup returns the sentinel entry, not NULL. Validate index data
# before walking it, and avoid signed overflow in the count and position checks.
voicechat_replace_once("${RNNOISE}/src/parse_lpcnet_weights.c"
[=[  if (a == NULL) return NULL;
  idx = a->data;
  remain = a->size/sizeof(int);]=]
[=[  if (a == NULL || a->name == NULL || a->size % sizeof(int) != 0 || nb_in < 4) return NULL;
  idx = a->data;
  remain = a->size/sizeof(int);]=])
voicechat_replace_once("${RNNOISE}/src/parse_lpcnet_weights.c"
[=[    if (remain < nb_blocks+1) return NULL;]=]
[=[    if (nb_out < 8 || nb_blocks < 0 || nb_blocks >= remain) return NULL;]=])
voicechat_replace_once("${RNNOISE}/src/parse_lpcnet_weights.c"
[=[      if (pos+3 >= nb_in || (pos&0x3)) return NULL;]=]
[=[      if (pos < 0 || pos > nb_in-4 || (pos&0x3)) return NULL;]=])

# Check allocation failures used by the buffer-backed JNI constructor. The
# original pointer must survive a failed realloc so cleanup can release it.
voicechat_replace_once("${RNNOISE}/src/denoise.c"
[=[  model = malloc(sizeof(*model));
  model->blob = NULL;]=]
[=[  model = malloc(sizeof(*model));
  if (model == NULL) return NULL;
  model->blob = NULL;]=])
voicechat_replace_once("${RNNOISE}/src/denoise.c"
[=[  st = malloc(rnnoise_get_size());
  ret = rnnoise_init(st, model);]=]
[=[  st = malloc(rnnoise_get_size());
  if (st == NULL) return NULL;
  ret = rnnoise_init(st, model);]=])
voicechat_replace_once("${RNNOISE}/src/parse_lpcnet_weights.c"
[=[  *list = calloc(capacity*sizeof(WeightArray), 1);
  while (len > 0) {]=]
[=[  *list = calloc(capacity*sizeof(WeightArray), 1);
  if (*list == NULL) return -1;
  while (len > 0) {]=])
voicechat_replace_once("${RNNOISE}/src/parse_lpcnet_weights.c"
[=[        *list = realloc(*list, capacity*sizeof(WeightArray));]=]
[=[        WeightArray *grown = realloc(*list, capacity*sizeof(WeightArray));
        if (grown == NULL) {
          free(*list);
          *list = NULL;
          return -1;
        }
        *list = grown;]=])
