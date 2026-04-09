# /// script
# requires-python = ">=3.10"
# dependencies = ["wn>=0.10"]
# ///
"""
Simple DanNet query via WN-LMF.

Run with:
    uv run wn_lmf_query.py

Interactive REPL (for copy-pasting individual lines):
    uv run --with wn python

Based on the example from the https://github.com/goodmami/wn README.
To clear the wn database between restarts: rm ~/.wn_data/wn.db
"""

import wn

wn.add("../export/wn-lmf/dannet-wn-lmf.xml.gz")

for synset in wn.synsets('land'):
    print((synset.lexfile() or "?") + ": " + (synset.definition() or "?"))
