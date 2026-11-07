#!/bin/sh
# Builds the Specman manual from the AsciiDoc source in two forms:
#   index.html           - single-page HTML with the table of contents on the left
#   specman-manual.pdf   - PDF (asciidoctor-pdf)
# Usage: ./build-manual.sh <specman-doc-dir> <output-dir>
# Needs: asciidoctor, asciidoctor-pdf (gems).
# Locally without Ruby: run it inside the asciidoctor/docker-asciidoctor image.
set -e

DOC_DIR="${1:-../specman/doc}"
OUT_DIR="${2:-manual}"
SOURCE="$DOC_DIR/specman-manual.adoc"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Images and example files referenced by the manual live next to the AsciiDoc source
find "$DOC_DIR" -maxdepth 1 -type f ! -name '*.adoc' ! -name 'specman-manual.pdf' -exec cp {} "$OUT_DIR/" \;

# Step and toolbar icons in chapter headings are referenced as ../src/main/resources/images/svg/... from the manual
ICON_DIR="$OUT_DIR/../src/main/resources/images"
mkdir -p "$ICON_DIR"
cp -r "$DOC_DIR/../src/main/resources/images/svg" "$ICON_DIR/"

# webfonts!: don't load fonts from Google, keeps the site free of third-party requests
# docinfo: adds the site header (manual-theme/docinfo*.html) so the manual looks like part of the website
THEME_DIR="$(cd "$(dirname "$0")" && pwd)/manual-theme"
asciidoctor -a 'webfonts!' -a toc=left -a toclevels=3 -a docinfo=shared -a "docinfodir=$THEME_DIR" -D "$OUT_DIR" -o index.html "$SOURCE"
asciidoctor-pdf -D "$OUT_DIR" -o specman-manual.pdf "$SOURCE"
