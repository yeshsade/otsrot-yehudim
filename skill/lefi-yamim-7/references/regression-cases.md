# Regression scope

The test suite includes synthetic Sukkot/Bereshit navigation cases inherited from HFNAYA. Those fixtures verify navigation behavior; they do not establish that the complete source booklets have been built or validated with version 7. Previous-week page numbers in old fixtures are case-specific and must never become defaults.

The current full integration case uses the supplied Toldot 845 interior and cover, with the four owner-confirmed source pages documented in source-review.md. Report integration results from the actual build validation.json.

Navigation policy regressions cover fixed anchors in portrait and landscape, corner-preserving checkbox shrink with matching click area, rejection of ink-only shrink, rejection of footer-to-header/side relocation, rejection of return-to-footer relocation, and the 25% shrink limit. The continuation image test compares explicit labels against the original 29-pixel generic rendering.

The owner clarified that translucent navigation over source text is intentional. Regression tests now require full-size fixed anchors over header/footer ink, while still rejecting coverage of checkboxes or another clickable control. Asset tests pin the original background alpha (84/255 continuation, 128/255 return at a plain background pixel).

Shulchan Aruch regressions use actual inherited caption images with synthetic mixed regular/bold study styles. They check all four columns, variable thirds for a short column, an entirely gray excluded column, rejection of missing/truncated study regions, rejection of an unknown boundary without font fallback, and portrait three-per-column behavior. The 845 integration case also rechecks all seven actual source pages and the old Wednesday result that omitted two columns.

The shared Gemara build regression now exercises the complete days build with three source pages: A1, A2 and B. It preserves occurrence pages [[5], [6], [7]], embeds logical pages [[5, 6], [7]], validates all saved navigation, and checks synchronized Yes/Yes/Off values after saving again. Source discovery/identity and the external full-week checker are mocked for this deliberately small fixture; production placement, PDF writing, grouping, joining and validation are real.
