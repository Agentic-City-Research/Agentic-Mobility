# AgenticMobility manuscript

LaTeX source for **AgenticMobility: An Open-Source Platform for Studying AI Agents and Collective Mobility Dynamics**.

## Project files

- `main.tex`: main document.
- `sections/`: manuscript sections.
- `appendices/`: appendix sources; `main.tex` includes `app_1.tex`.
- `pic/`: figure assets, including PDF figures.
- `references.bib`: bibliography.

## Build

Install a TeX distribution such as TeX Live or TinyTeX with pdfLaTeX, BibTeX, and latexmk, plus the packages used by `main.tex`.

Run this command from the repository root:

```sh
latexmk -pdf -synctex=1 -interaction=nonstopmode -file-line-error -outdir=build main.tex
```

The compiled document is `build/main.pdf`. Build output and local archives are excluded from version control; PDF figure assets in `pic/` are tracked.

For Overleaf, upload the source files and choose `main.tex` as the main document with pdfLaTeX as the compiler.

## Collaboration

Before editing, retrieve the latest changes:

```sh
git pull --ff-only
```

After editing, review and upload your changes:

```sh
git status
git add main.tex references.bib sections appendices pic README.md .gitignore .gitattributes
git diff --cached
git commit -m "Describe the manuscript changes"
git push
```

If a pull reports diverging changes, reconcile them before pushing.
