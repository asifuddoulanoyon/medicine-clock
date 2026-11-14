"""Shrink the Bangla voice model (about 110 MB -> 30 MB) and keep the details the speech engine reads from it."""
import sys
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

src, dst = sys.argv[1], sys.argv[2]
quantize_dynamic(src, dst, weight_type=QuantType.QUInt8)
original, small = onnx.load(src), onnx.load(dst)
meta = {p.key: p.value for p in original.metadata_props}
del small.metadata_props[:]
for k, v in meta.items():
    e = small.metadata_props.add()
    e.key, e.value = k, v
onnx.save(small, dst)
print("metadata keys:", sorted(meta))
