package com.hro_basti.timberella.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class VersionComparator {
    private VersionComparator() {}

    public static int compare(String a, String b) {
        Version va = Version.parse(a);
        Version vb = Version.parse(b);
        return va.compareTo(vb);
    }

    public static boolean isGreater(String a, String b) {
        return compare(a, b) > 0;
    }

    static final class Version implements Comparable<Version> {
        final List<Integer> nums;
        final String letterSuffix; // e.g., "a", "b" from main segment like 1.0a
        final Pre pre; // prerelease like alpha, beta, rc with optional number

        Version(List<Integer> nums, String letterSuffix, Pre pre) {
            this.nums = nums;
            this.letterSuffix = letterSuffix;
            this.pre = pre;
        }

        static Version parse(String s) {
            if (s == null) s = "";
            String raw = s.trim();
            String lower = raw.toLowerCase(Locale.ROOT);

            // split pre-release part (after first '-')
            String main = lower;
            String pre = null;
            int dash = lower.indexOf('-');
            if (dash >= 0) {
                main = lower.substring(0, dash);
                pre = lower.substring(dash + 1);
            }

            // parse numeric parts and potential trailing letters on the last segment
            String[] segs = main.split("\\.");
            List<Integer> nums = new ArrayList<>();
            String letterSuffix = null;
            for (int i = 0; i < segs.length; i++) {
                String seg = segs[i];
                if (seg.isEmpty()) { nums.add(0); continue; }
                int j = 0;
                while (j < seg.length() && Character.isDigit(seg.charAt(j))) j++;
                String numStr = j > 0 ? seg.substring(0, j) : "0";
                int val;
                try { val = Integer.parseInt(numStr); } catch (Exception e) { val = 0; }
                nums.add(val);
                if (i == segs.length - 1) {
                    String tail = seg.substring(j);
                    if (!tail.isEmpty() && allLetters(tail)) {
                        letterSuffix = tail;
                    }
                }
            }
            if (nums.isEmpty()) nums.add(0);

            Pre preObj = Pre.parse(pre);
            return new Version(nums, letterSuffix, preObj);
        }

        static boolean allLetters(String s) {
            for (int i = 0; i < s.length(); i++) if (!Character.isLetter(s.charAt(i))) return false;
            return true;
        }

        @Override
        public int compareTo(Version o) {
            // 1) compare numeric parts
            int max = Math.max(this.nums.size(), o.nums.size());
            for (int i = 0; i < max; i++) {
                int a = i < this.nums.size() ? this.nums.get(i) : 0;
                int b = i < o.nums.size() ? o.nums.get(i) : 0;
                if (a != b) return Integer.compare(a, b);
            }
            // 2) compare letter suffix on main version (e.g., 1.0a > 1.0)
            boolean aHas = this.letterSuffix != null && !this.letterSuffix.isEmpty();
            boolean bHas = o.letterSuffix != null && !o.letterSuffix.isEmpty();
            if (aHas && !bHas) return 1;
            if (!aHas && bHas) return -1;
            if (aHas && bHas) {
                int c = this.letterSuffix.compareTo(o.letterSuffix);
                if (c != 0) return c;
            }
            // 3) compare prerelease (release > prerelease)
            if (this.pre == null && o.pre != null) return 1;
            if (this.pre != null && o.pre == null) return -1;
            if (this.pre == null) return 0;
            return this.pre.compareTo(o.pre);
        }

        static final class Pre implements Comparable<Pre> {
            final String label; // alpha, beta, rc, others
            final int number;   // e.g., beta2 -> 2

            Pre(String label, int number) {
                this.label = label;
                this.number = number;
            }

            static Pre parse(String s) {
                if (s == null || s.isEmpty()) return null;
                String t = s.trim().toLowerCase(Locale.ROOT);
                // allow formats like beta, beta2, rc1, alpha10
                int i = 0;
                while (i < t.length() && Character.isLetter(t.charAt(i))) i++;
                String name = i > 0 ? t.substring(0, i) : t;
                String numStr = i < t.length() ? t.substring(i) : "";
                int n = 0;
                try { if (!numStr.isEmpty()) n = Integer.parseInt(numStr); } catch (Exception ignored) {}
                return new Pre(name, n);
            }

            static int rank(String name) {
                if (name == null) return -1;
                switch (name) {
                    case "alpha":
                        return 0;
                    case "beta":
                        return 1;
                    case "rc":
                        return 2;
                    default:
                        // Unknown prerelease tags are considered very early (before alpha)
                        return -1;
                }
            }

            @Override
            public int compareTo(Pre o) {
                if (Objects.equals(this.label, o.label)) {
                    return Integer.compare(this.number, o.number);
                }
                int ra = rank(this.label);
                int rb = rank(o.label);
                if (ra != rb) return Integer.compare(ra, rb);
                // fallback lexicographic if same rank (unknown labels)
                return this.label.compareTo(o.label);
            }
        }
    }
}
