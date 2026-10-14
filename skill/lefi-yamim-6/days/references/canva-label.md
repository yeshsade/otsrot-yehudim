# Canva Label

Use this only after `scripts/build_lefi_yamim.py build` has produced the final PDF and printed `COVER_CIRCLE_HEX`.

## Fixed Template Values

- Design ID: `DAHJXBdg69U`
- Foreground rectangle to update: `PBFFkNdQVQlDr6kJ-LBrwT1qQfqnC8GCb-LBrHkZHdwvz2CHXp`
- Shadow rectangle to leave untouched: `PBFFkNdQVQlDr6kJ-LBrwT1qQfqnC8GCb-LBfCDbLbMKldZ7c8`
- Parsha text element: `PBFFkNdQVQlDr6kJ-LBrwT1qQfqnC8GCb-LB5WjqF7SnXMn0bh`
- Date text element: `PBFFkNdQVQlDr6kJ-LBrwT1qQfqnC8GCb-LBDQ3CHsp2QjJH7B`

## Procedure

1. Split the user label at `/`:
   - `parsha_line1`: text before `/`, trimmed.
   - `parsha_line2`: text after `/`, trimmed.
2. Upload a solid color image generated from the detected cover color:
   - URL: `https://placehold.co/892x514/{hex_color}/{hex_color}.png`
   - Save the returned `asset_id`.
3. Start an editing transaction for design `DAHJXBdg69U`.
4. Perform these operations in one batch:
   - `update_fill` on the foreground rectangle with `asset_id`.
   - `replace_text` on the parsha element with `parsha_line1`.
   - `replace_text` on the date element with `parsha_line2`.
   - `format_text` on the date element with `color="#FFFFFF"` and `font_weight="bold"`.
5. Commit the transaction.
6. Export the design as PNG with transparent background and pro quality.
7. Pass the exported PNG URL to:

```bash
python scripts/build_lefi_yamim.py attach-label --pdf "<final.pdf>" --label-url "<exported-canva-png-url>"
```
