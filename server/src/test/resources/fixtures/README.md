# Synthetic media fixture

`synthetic-video.mp4.base64` is a Base64-encoded, one-second solid blue clip generated for this repository. It contains no athlete, private data, or external footage. The title metadata deliberately exercises the bug where container metadata became knowledge evidence. Tests decode it in memory; FFmpeg is not required for Java tests.

Generation command (FFmpeg 8.1.1):

```sh
ffmpeg -f lavfi -i color=c=blue:s=320x240:r=10 -t 1 -an -c:v libx264 -pix_fmt yuv420p -metadata "title=Synthetic MCP upload test" synthetic-video.mp4
```
