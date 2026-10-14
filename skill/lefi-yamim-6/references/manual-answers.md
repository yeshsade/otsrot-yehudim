# Manual answers contract

Never hand-edit `topic_ranges`, `day_pages`, `daf_yomi.day_runs`, or
`topic_starts_original`. Record the user's answers in a separate JSON file and
run `scripts/apply_manual_answers.py`. Use only its output analyses for builds.

Required answer keys are `booklet`, `previous_week_pages`,
`previous_week_gemara_page`, `no_parsha_this_week`, `topic_ranges`, and exactly
the active `topic_starts_original` entries. Declare absent parsha/hashlamot in
`absent_topics`; omit their TOC starts. `daf_yomi_duplicate_amud` is optional.
For days builds, use an independent `source_review_report` from the review
script instead of requesting a large map approval. `source_map_confirmed_by_user`
may be true only after actual user review, never inherited from an older analysis.
Test fixtures reconstructed from previously
approved PDFs must record that provenance and must not count as answers for
a future booklet.

`daf_yomi_short_days` may record a day with `source_pages` and a nonempty
`reason`; it applies only to that exact day's actual source pages. Missing
previous-week Gemara answers are an error; explicit `null` means none.

Previous-week pages are separate from current-week ranges. In the topics
edition, a previous-week page is the TOC target when it is the first page that
readers must see.
