#!/usr/bin/env bash
# Side by side, labelled, trimmed to the same start; then a palette GIF.
set -euo pipefail
label() { echo "drawbox=x=0:y=0:w=iw:h=64:color=0x1F2933@1:t=fill,drawtext=text='$1':fontcolor=white:fontsize=34:x=(w-text_w)/2:y=16"; }
ffmpeg -loglevel error -y -i /work/a.mp4 -i /work/b.mp4 -filter_complex \
  "[0:v]tpad=stop_mode=clone:stop_duration=3,fps=12,scale=360:-2,pad=iw:ih+64:0:64,$(label 'pixel-a')[a];\
   [1:v]tpad=stop_mode=clone:stop_duration=3,fps=12,scale=360:-2,pad=iw:ih+64:0:64,$(label 'pixel-b')[b];\
   [a][b]hstack=inputs=2:shortest=0,pad=iw+24:ih+16:12:8:color=white[v]" \
  -map "[v]" -c:v libx264 -pix_fmt yuv420p -crf 26 /docs/two-phones.mp4
ffmpeg -loglevel error -y -i /docs/two-phones.mp4 -vf "fps=8,split[s0][s1];[s0]palettegen=max_colors=96[p];[s1][p]paletteuse=dither=bayer:bayer_scale=4" /docs/two-phones.gif
ls -la /docs/two-phones.*
