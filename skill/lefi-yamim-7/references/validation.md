# Delivery validation

The builder records input hashes, source page ownership, normalized source-only layout and actual planned widgets/links. It validates the reopened saved PDF, not only in-memory objects.

Required checks: unchanged source hashes; expected source coverage; source-text equality; valid internal link targets and clickable rectangles; no checkbox/text, checkbox/checkbox or checkbox/link collisions; map fields and page lists equal actual widgets; semantic labels/grouping; shared Gemara parent/Kids fields; size within the allowed budget. The independent Claude checker runs after these checks, and its content hash is recorded. Its passing count policy does not replace the skill's measured geometry checks, including Tehillim.

Previous-week completion pages retain content and reciprocal links but must have zero widgets and zero progress checks. The saved-PDF validator must reject both a `week_offset: -1` check and a check incorrectly labeled current-week whose page belongs to the previous week. This owner correction applies to every topic and both editions.

Render representative Nach, Gemara, Mishnah Berurah, landscape and straight study pages, continuation buttons and complete daily Tehillim boundaries. Pixel collisions detect raster ink as well as extracted glyphs. A known source-selection or visual failure must remain a failed candidate until resolved.

The embedded schema is `otzarot.progress-map/1`, attachment name `otzarot-progress-map.v1.json`. Extra `reading_regions` identifies exact study geometry where two days share a column/half. The reader must interpret this when assigning reading portions; a half-only page map is insufficient for such sources.

Shared Gemara widgets are a standard parent field with children. For scripted state changes, use `gemara_checkboxes.set_checkbox_state` so the parent value and all child appearances change together. A single PyMuPDF widget update can leave another child's appearance stale; test the intended viewer separately before claiming viewer-specific synchronization.

Do not claim cross-booklet compatibility, every PDF viewer, visual approval or an unbuilt edition from a single successful run. Report actual validation and material limitations.
