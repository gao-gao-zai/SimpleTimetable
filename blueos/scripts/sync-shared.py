from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = root / 'app/src/platform/snapshot-reader.js'
for target in [root / 'app/src/widgets/Now/lib/snapshot-reader.js', root / 'watchface/src/watch3000/lib/snapshot-reader.js']:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(source.read_bytes())
