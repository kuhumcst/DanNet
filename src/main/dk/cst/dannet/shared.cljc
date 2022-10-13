(ns dk.cst.dannet.shared)

(defn setify
  [x]
  (when x
    (if (set? x) x #{x})))
