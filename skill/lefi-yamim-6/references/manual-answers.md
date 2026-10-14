# Manual answers contract

Never hand-edit `topic_ranges`, `day_pages`, `daf_yomi.day_runs`, or
`topic_starts_original`. Record the user's answers in a separate JSON file and
run `scripts/apply_manual_answers.py`. Use only its output analyses for builds.

Required answer keys are `booklet`, `previous_week_pages`,
`previous_week_gemara_page`, `no_parsha_this_week`, `topic_ranges`, and all 15
`topic_starts_original` entries. `daf_yomi_duplicate_amud` is optional.

Previous-week pages are separate from current-week ranges. In the topics
edition, a previous-week page is the TOC target when it is the first page that
readers must see.
