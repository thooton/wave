# Source-reader fixtures

These files replace a single file in `../example-source/` for the core test
suite. Replace `applications/list/package.json` for package fixtures and
`applications/list/icon.png` for the bad icon. Replace the root `wave.json`
for the bad header.

The reader and `wavesrc lint` agree that the example is valid and the first
variants other than `unknown-type` are invalid (`huge-icon` is a valid PNG
that declares 65535x65535 pixels in under 100 bytes). On disk Wave isolates invalid packages and icons
rather than hiding the whole source. `unknown-type` deliberately tests the
reader/writer distinction: the v1 linter refuses publication; Wave lists the
metadata with “Needs a newer Wave”.
