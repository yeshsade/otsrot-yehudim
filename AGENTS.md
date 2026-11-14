# Booklet preparation: active workflow is Skill 7

For אוצרות יהודים PDF booklet preparation, use the production branch
`gpt/skill7-implementation` in this repository. Read its
`skill/lefi-yamim-7/START-HERE.md`, `skill/lefi-yamim-7/SKILL.md` and
`android-reader-gpt/docs/SKILL7-FOR-CLAUDE.md` before acting. Fetch the branch;
when the checkout is clean, switch to it. Preserve any existing work. A cloud
checkout starting on main does not mean the historical MOM or HEIDER workflow
is the active production skill. Do not merge legacy snapshots to select it.

A user uploading cover/interior and asking to prepare a booklet authorizes
both editions (days and topics), unless explicitly limited. Do not ask them
again which edition, which branch, for a previous-chat summary, or for fonts
already committed in Skill 7. Read issue/parsha/date from the cover and reuse
existing source-hash-bound confirmations for those exact inputs. Locate already
uploaded files before asking the user to upload them again.

Keep pristine source PDFs, fixed button anchors and original page dimensions.
Use the approved hash-bound repository font profile and its readiness check.
Source completion questions include an actual page preview. For Gemara
questions, give one day and its full one-based interior source range, then ask
which amud is duplicated: A, B or both. Do not lead with a predicted answer.
Photo previews for duplication questions are supplied if requested. Wait for
and record each answer before the next unresolved day. Previous-week pages
receive no progress boxes. Preserve every duplicate and commentary.

Run saved-PDF validation and Claude's independent checker before delivery.
Deliver each edition as a separate PDF link and record sources, owner answers
and QA in GitHub. Do not require a ZIP or user-managed font installation.
