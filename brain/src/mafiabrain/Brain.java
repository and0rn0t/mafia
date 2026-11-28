package mafiabrain;

import java.util.*;

/**
 * 마피아 봇의 "가능한 세계" 추론(mtest.sk 에서 skript-reflect 로 부른다). 게임 규칙은 모르고, Skript 가 넘겨 준 공개 사실만 본다.
 *
 * 세계 = 각 참가자의 숨은 종류(M 마피아, S 보조, N 중립, C 시민 팀) 배치. 직업 구성(마피아·보조·중립 수)은 인원으로 정해져 있어
 * 그 수에 맞는 모든 배치를 만든다(12인 약 6천 가지, 너무 많으면 무작위로 일부만). 배치마다 공개 사실과 얼마나 잘 맞는지 가중치를 매기고,
 * 가중치로 평균 내 "x 가 마피아일 확률"을 낸다.
 *
 * 가중치(공개 사실과 맞는 정도):
 *  - 시민 팀(C)은 사실대로 말한다: 한 명뿐인 직업을 C 둘이 주장하면 거의 불가능(광인이 있을 수 있으면 조금 더 가능).
 *  - 경찰을 주장한 C 의 결과는 맞다("마피아" → 대상이 M, "아님" → 대상이 M 이 아님).
 *  - 거짓말이 확인된 사람(liar), 모순이 쌓인 사람(cx)은 C 일 가능성이 낮다.
 *  - 밤에 죽은 사람은 마피아가 아닐 가능성이 크다(마피아는 같은 편을 죽이지 않는다. 중립 살해자는 있을 수 있다).
 *  - 마피아 팀(M, S)·중립(N)의 직업 주장은 아무 직업이나 될 수 있다(거짓말).
 *
 * 사실 문자열: 항목은 ';', 칸은 '|'.
 *   comp|마피아수|보조수|중립수   p|id(참가자, 순서대로 전부)   cl|id|직업   pub|id|M/S/N/C   nd|id   cx|id|점수   liar|id
 *   pol|경찰주장자|대상|mafia/town   mad|1(광인이 있을 수 있음)
 * 관점(view): me|M/S/N/C   role|내 진짜 직업(시민이면, 같은 직업 주장자는 가짜)   k|id|M(마피아)/T(마피아 아님)/E(시민 아님)/X(마피아 팀: M 또는 S)
 */
public final class Brain {
    static final int M = 0, S = 1, N = 2, C = 3;
    static final Set<String> UNIQUE = new HashSet<>(Arrays.asList(
            "police", "doctor", "reporter", "detective", "soldier", "politician", "terrorist", "guardian", "shaman", "vigilante", "thug"));
    static final int CAP = 40000;

    /** 공개 사실 */
    static final class Facts {
        List<String> ids = new ArrayList<>();
        Map<String, Integer> idx = new HashMap<>();
        int m, s, nn;
        String[] claim;
        int[] pub;
        boolean[] nd, liar;
        double[] cx;
        List<int[]> pol = new ArrayList<>(); // {경찰주장자, 대상, 1=마피아}
        boolean mad;

        Facts copy() {
            Facts f = new Facts();
            f.ids = ids; f.idx = idx; f.m = m; f.s = s; f.nn = nn; f.mad = mad;
            f.claim = claim.clone(); f.pub = pub.clone(); f.nd = nd.clone(); f.liar = liar.clone(); f.cx = cx.clone();
            f.pol = new ArrayList<>(pol);
            return f;
        }

        void alloc() {
            int n = ids.size();
            claim = new String[n]; pub = new int[n]; Arrays.fill(pub, -1);
            nd = new boolean[n]; liar = new boolean[n]; cx = new double[n];
        }

        void apply(String item) {
            if (item.isEmpty()) return;
            String[] f = item.split("\\|", -1);
            Integer a = f.length > 1 ? idx.get(f[1]) : null;
            switch (f[0]) {
                case "cl": if (a != null && f.length > 2) claim[a] = f[2].isEmpty() || f[2].equals("-") ? null : f[2]; break;
                case "pub": if (a != null && f.length > 2) pub[a] = type(f[2]); break;
                case "nd": if (a != null) nd[a] = true; break;
                case "liar": if (a != null) liar[a] = true; break;
                case "cx": if (a != null && f.length > 2) cx[a] = num(f[2]); break;
                case "pol":
                    if (a != null && f.length > 3) {
                        Integer t = idx.get(f[2]);
                        if (t != null) {
                            int r = f[3].equals("mafia") ? 1 : 0;
                            boolean dup = false;
                            for (int[] q : pol) if (q[0] == a && q[1] == t && q[2] == r) dup = true;
                            if (!dup) pol.add(new int[]{a, t, r});
                        }
                    }
                    break;
                case "mad": mad = true; break;
                default: break;
            }
        }
    }

    static Facts base;
    static byte[] worlds;      // 세계마다 참가자 수만큼 종류
    static double[] weight;    // 세계 가중치(공개 사실)
    static int count;
    static final Map<String, double[][]> views = new HashMap<>();
    static final Random rnd = new Random();

    static int type(String t) {
        switch (t) { case "M": return M; case "S": return S; case "N": return N; default: return C; }
    }

    static double num(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    static Facts parse(String text) {
        Facts f = new Facts();
        String[] items = text.split(";");
        for (String it : items) {
            String[] p = it.split("\\|", -1);
            if (p[0].equals("p") && p.length > 1 && !f.idx.containsKey(p[1])) { f.idx.put(p[1], f.ids.size()); f.ids.add(p[1]); }
            if (p[0].equals("comp") && p.length > 3) { f.m = (int) num(p[1]); f.s = (int) num(p[2]); f.nn = (int) num(p[3]); }
        }
        f.alloc();
        for (String it : items) f.apply(it);
        return f;
    }

    /** 공개 사실을 새로 넣고 세계를 다시 만든다. 관점 결과는 지운다. 세계 수를 돌려준다 */
    public static synchronized int load(String text) {
        base = parse(text);
        views.clear();
        build(base);
        weight = weigh(base);
        return count;
    }

    // ---- 세계 만들기: 공개된 직업(pub)은 고정. 너무 많으면 무작위로 CAP 개
    static void build(Facts f) {
        int n = f.ids.size();
        int need = f.m + f.s + f.nn;
        if (n == 0 || need > n) { worlds = new byte[0]; count = 0; return; }
        double total = choose(n, f.m) * choose(n - f.m, f.s) * choose(n - f.m - f.s, f.nn);
        List<byte[]> out = new ArrayList<>();
        if (total <= CAP) {
            byte[] cur = new byte[n];
            Arrays.fill(cur, (byte) C);
            place(f, cur, 0, M, f.m, out);
        } else {
            Set<String> seen = new HashSet<>();
            int tries = 0;
            while (out.size() < CAP && tries < CAP * 4) {
                tries++;
                byte[] cur = sample(f, n);
                if (cur != null && seen.add(new String(cur, java.nio.charset.StandardCharsets.ISO_8859_1))) out.add(cur);
            }
        }
        count = out.size();
        worlds = new byte[count * n];
        for (int i = 0; i < count; i++) System.arraycopy(out.get(i), 0, worlds, i * n, n);
    }

    static double choose(int n, int k) {
        if (k < 0 || k > n) return 0;
        double r = 1;
        for (int i = 0; i < k; i++) r = r * (n - i) / (i + 1);
        return r;
    }

    static boolean allowed(Facts f, int i, int t) {
        return f.pub[i] < 0 || f.pub[i] == t;
    }

    // 종류 t 를 left 명 더 놓는다(앞 번호부터 차례로, 겹치지 않게). M → S → N 순서
    static void place(Facts f, byte[] cur, int from, int t, int left, List<byte[]> out) {
        int n = cur.length;
        if (left == 0) {
            if (t == M) { place(f, cur, 0, S, f.s, out); return; }
            if (t == S) { place(f, cur, 0, N, f.nn, out); return; }
            for (int i = 0; i < n; i++) if (cur[i] == C && !allowed(f, i, C)) return;
            out.add(cur.clone());
            return;
        }
        for (int i = from; i < n; i++) {
            if (cur[i] != C || !allowed(f, i, t)) continue;
            cur[i] = (byte) t;
            place(f, cur, i + 1, t, left - 1, out);
            cur[i] = (byte) C;
            if (out.size() > CAP) return;
        }
    }

    static byte[] sample(Facts f, int n) {
        byte[] cur = new byte[n];
        Arrays.fill(cur, (byte) C);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        Collections.shuffle(order, rnd);
        int[] want = {f.m, f.s, f.nn};
        // 공개된 직업 먼저
        for (int i = 0; i < n; i++) if (f.pub[i] >= 0 && f.pub[i] != C) { if (want[f.pub[i]] <= 0) return null; cur[i] = (byte) f.pub[i]; want[f.pub[i]]--; }
        for (int t = M; t <= N; t++) {
            for (int i : order) {
                if (want[t] == 0) break;
                if (cur[i] == C && f.pub[i] < 0) { cur[i] = (byte) t; want[t]--; }
            }
            if (want[t] > 0) return null;
        }
        return cur;
    }

    // ---- 가중치
    static double[] weigh(Facts f) {
        int n = f.ids.size();
        double eps = f.mad ? 0.15 : 0.04;
        double[] w = new double[count];
        // 한 명뿐인 직업별 주장자
        Map<String, List<Integer>> byRole = new HashMap<>();
        for (int i = 0; i < n; i++) if (f.claim[i] != null && UNIQUE.contains(f.claim[i])) byRole.computeIfAbsent(f.claim[i], k -> new ArrayList<>()).add(i);
        double[] cw = new double[n];
        for (int i = 0; i < n; i++) {
            double x = 1;
            if (f.liar[i]) x *= 0.02;
            if (f.cx[i] > 0) x *= Math.max(0.03, Math.exp(-f.cx[i] / 110.0));
            cw[i] = x;
        }
        // 직업 주장의 가능성: 시민 팀은 자기 진짜 직업을 말하고(시민 팀 직업은 고르게 나온다), 거짓말하는 쪽은 경찰·주술사를 조금 더 자주 사칭한다
        int cTot = Math.max(1, n - f.m - f.s - f.nn);
        double lc = 1.0 / cTot;
        double[] ll = new double[n];
        for (int i = 0; i < n; i++) ll[i] = f.claim[i] == null ? 1 : ("police".equals(f.claim[i]) || "shaman".equals(f.claim[i]) ? 0.10 : 0.08);
        for (int k = 0; k < count; k++) {
            int o = k * n;
            double x = 1;
            boolean polOk = false, docOk = false;
            for (int i = 0; i < n; i++) {
                int t = worlds[o + i];
                if (t == C) {
                    x *= cw[i];
                    // 경찰·의사는 반드시 있다(시민 팀): 그 직업을 주장했거나 아직 직업을 안 밝힌 시민 팀이 있어야 한다
                    if (f.claim[i] == null || "police".equals(f.claim[i])) polOk = true;
                    if (f.claim[i] == null || "doctor".equals(f.claim[i])) docOk = true;
                } else if (f.nd[i]) {
                    x *= t == M ? 0.08 : (t == S ? 0.5 : 0.8);
                }
                if (f.claim[i] != null) x *= t == C ? lc : ll[i];
            }
            if (!polOk) x *= eps;
            if (!docOk) x *= eps;
            for (List<Integer> l : byRole.values()) {
                if (l.size() < 2) continue;
                int c = 0;
                for (int i : l) if (worlds[o + i] == C) c++;
                for (int j = 1; j < c; j++) x *= eps;
            }
            for (int[] p : f.pol) {
                if (worlds[o + p[0]] != C || !"police".equals(f.claim[p[0]])) continue;
                boolean isM = worlds[o + p[1]] == M;
                if ((p[2] == 1) != isM) x *= eps;
            }
            // 거짓말하는 쪽의 경찰 결과: "마피아" 라고 지목하는 일은 드물다(대개 "아니에요")
            for (int[] p : f.pol) {
                if (worlds[o + p[0]] == C || !"police".equals(f.claim[p[0]])) continue;
                x *= p[2] == 1 ? 0.2 : 0.8;
            }
            w[k] = x;
        }
        return w;
    }

    /** 관점 하나(봇 id, 공개 관점은 "")의 확률을 계산해 둔다. 맞는 세계가 없으면 공개 관점 그대로 */
    public static synchronized boolean view(String key, String me, String constraints) {
        if (base == null) return false;
        Facts f = base;
        int n = f.ids.size();
        int meI = me == null || me.isEmpty() ? -1 : f.idx.getOrDefault(me, -1);
        int meT = -1;
        String myRole = null;
        Map<Integer, Character> k = new HashMap<>();
        for (String it : constraints.split(";")) {
            String[] p = it.split("\\|", -1);
            if (p[0].equals("me") && p.length > 1) meT = type(p[1]);
            else if (p[0].equals("role") && p.length > 1 && !p[1].isEmpty()) myRole = p[1];
            else if (p[0].equals("k") && p.length > 2) { Integer a = f.idx.get(p[1]); if (a != null && !p[2].isEmpty()) k.put(a, p[2].charAt(0)); }
        }
        double eps = f.mad ? 0.15 : 0.04;
        double[][] r = new double[3][n];
        double tot = 0;
        for (int w = 0; w < count; w++) {
            int o = w * n;
            if (meI >= 0 && meT >= 0 && worlds[o + meI] != meT) continue;
            boolean ok = true;
            for (Map.Entry<Integer, Character> e : k.entrySet()) {
                int t = worlds[o + e.getKey()];
                switch (e.getValue()) {
                    case 'M': ok = t == M; break;
                    case 'T': ok = t != M; break;
                    case 'E': ok = t != C; break;
                    case 'X': ok = t == M || t == S; break;
                    case 'C': ok = t == C; break;
                    default: break;
                }
                if (!ok) break;
            }
            if (!ok) continue;
            double x = weight[w];
            // 내 진짜 직업(한 명뿐)을 다른 사람이 주장하면 그 사람은 시민 팀이 아니다(광인이면 조금 가능)
            if (myRole != null && UNIQUE.contains(myRole) && meT == C) {
                for (int i = 0; i < n; i++) if (i != meI && myRole.equals(f.claim[i]) && worlds[o + i] == C) x *= eps;
            }
            if (x <= 0) continue;
            tot += x;
            for (int i = 0; i < n; i++) {
                int t = worlds[o + i];
                if (t < C) r[t][i] += x;
            }
        }
        if (tot <= 0) {
            if (!key.isEmpty() && views.containsKey("")) { views.put(key, views.get("")); return true; }
            return false;
        }
        for (int t = 0; t < 3; t++) for (int i = 0; i < n; i++) r[t][i] /= tot;
        views.put(key, r);
        return true;
    }

    static double get(String key, String id, int t) {
        double[][] r = views.get(key);
        if (r == null || base == null) return -1;
        Integer i = base.idx.get(id);
        if (i == null) return -1;
        return r[t][i];
    }

    /** 관점 key 에서 id 가 마피아(M)일 확률. 계산 전이면 -1 */
    public static synchronized double pm(String key, String id) { return get(key, id, M); }
    /** 보조(S)일 확률 */
    public static synchronized double ps(String key, String id) { return get(key, id, S); }
    /** 중립(N)일 확률 */
    public static synchronized double pn(String key, String id) { return get(key, id, N); }

    /**
     * 미리 따져 보기(마피아 팀 봇): 공개 사실에 extra(같은 형식의 항목들)를 더했을 때, 시민 쪽(공개 관점)이 보는 mates(쉼표로 나눈 id)의
     * "마피아 팀(M 또는 S)일 확률" 합. 계산할 수 없으면 -1
     */
    public static synchronized double risk(String extra, String mates) {
        if (base == null) return -1;
        Facts f = base;
        double[] w = weight;
        byte[] keepW = worlds;
        int keepC = count;
        boolean rebuilt = false;
        if (extra != null && !extra.isEmpty()) {
            f = base.copy();
            for (String it : extra.split(";")) f.apply(it);
            boolean pubChanged = !Arrays.equals(f.pub, base.pub);
            if (pubChanged) { build(f); rebuilt = true; }
            w = weigh(f);
        }
        int n = f.ids.size();
        List<Integer> ms = new ArrayList<>();
        for (String s : mates.split(",")) { Integer i = f.idx.get(s.trim()); if (i != null) ms.add(i); }
        double tot = 0, sum = 0;
        for (int k = 0; k < count; k++) {
            double x = w[k];
            if (x <= 0) continue;
            tot += x;
            int o = k * n;
            for (int i : ms) { int t = worlds[o + i]; if (t == M || t == S) sum += x; }
        }
        if (rebuilt) { worlds = keepW; count = keepC; }
        if (tot <= 0) return -1;
        return sum / tot;
    }

    /** 세계 수(확인용) */
    public static synchronized int size() { return count; }
}
