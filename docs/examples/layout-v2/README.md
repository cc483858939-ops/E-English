# Synthetic v2 contract fixture

These files are entirely invented and contain no Cambridge question text. `part.json` represents ten legacy questions. `layout.json` covers all ten in eight groups: image choice, shared multiple choice, matching, plain fill, paired fill, table, flow, and image overlay fill. `content.html` exercises source-only text; `map.png` exercises declared image dimensions and reviewed overlay coordinates. `context.json` supplies the image/source metadata and the matching synthetic review record to the future offline validator. It is **not** a pack entry.

`build_fixture.py` is the deterministic fixture source. Run it from any working directory; it writes only files beside itself. The passing fixture requires the exact `part.json` bytes because `layout.json.basePartSha256` hashes those bytes. The PNG and HTML hashes are also generated from their exact bytes.

This fixture specifies the data contract, not current Android support. Existing import code accepts layout schema v1 only. The failure mutations and expected reason codes are in `../../layout-schema-v2-contract.md`.
