import wn

# based on the example from the https://github.com/goodmami/wn README
# (to clear db between restarts: rm ~/.wn_data/wn.db)

wn.add("../export/wn-lmf/dannet-wn-lmf.xml.gz")
ss = wn.synsets('vinde', pos='v')[0]
print(ss.definition() + " (" + ss.lexfile() + ")")
