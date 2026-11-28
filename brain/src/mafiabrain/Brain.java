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
 *  - 경찰·의사는 반드시 있다(그 직업을 주장했거나 직업을 안 밝힌 C 가 있어야 한다).
 *  - 직업 주장의 가능성: C 는 자기 직업을(시민 팀 직업은 고르게), 거짓말하는 쪽은 경찰·주술사를 조금 더 자주 사칭한다.
 *  - 경찰을 주장한 C 의 결과는 맞다. 거짓말하는 쪽이 경찰을 사칭해 "마피아"라고 지목하는 일은 드물다.
 *  - 탐정을 주장한 C 의 보고는 맞다: 대상이 C 라면 그 사람의 직업 주장·보고(밤에 능력을 썼는지, 누구에게)와 맞아야 한다.
 *  - 의사를 주장한 C 가 살렸다는 사람은 그 밤에 죽지 않는다. 수호자를 주장한 C 가 지킨 사람이 죽었는데 수호자가 살아 있으면 안 맞는다.
 *  - 공개된 투표(말한 투표): 마피아 팀은 마피아 팀원을 잘 찍지 않는다.
 *  - 거짓말이 확인된 사람(liar), 모순이 쌓인 사람(cx)은 C 일 가능성이 낮다.
 *  - 밤에 죽은 사람은 마피아가 아닐 가능성이 크다(마피아는 같은 편을 죽이지 않는다. 중립 살해자는 있을 수 있다).
 *
 * 사실 문자열: 항목은 ';', 칸은 '|'.
 *   comp|마피아수|보조수|중립수   p|id(참가자, 순서대로 전부)   mad|1(광인이 있을 수 있음)
 *   cl|id|직업   pub|id|M/S/N/C   nd|id   dd|id|날(그날 아침 발표된 밤 사망)   cx|id|점수   liar|id
 *   pol|경찰주장자|대상|mafia/town   rp|말한사람|날|직업|대상(-)|결과(-)   pv|투표한사람|대상
 * 관점(view): me|M/S/N/C   role|내 진짜 직업(시민이면, 같은 직업 주장자는 가짜)   k|id|M(마피아)/T(마피아 아님)/E(시민 아님)/X(마피아 팀)/C(시민 팀)
 */
public final class Brain {
    static final int M = 0, S = 1, N = 2, C = 3;
    static final Set<String> UNIQUE = new HashSet<>(Arrays.asList(
            "police", "doctor", "reporter", "detective", "soldier", "politician", "terrorist", "guardian", "shaman", "vigilante", "thug"));
    /** 밤에 대상에게 능력을 쓰는 시민 팀 직업(탐정에게 "능력을 썼다"로 보일 수 있는 것) */
    static final Set<String> ACTS = new HashSet<>(Arrays.asList(
            "police", "doctor", "detective", "thug", "guardian", "shaman", "reporter", "vigilante"));
    static final int CAP = 40000;

    /** 밤 보고 하나 */
    static final class Rep {
        int who, day, tgt = -1, y = -1; String role, res;
    }

    /** 공개 사실 */
    static final class Facts {
        List<String> ids = new ArrayList<>();
        Map<String, Integer> idx = new HashMap<>();
        List<String> items = new ArrayList<>();
        int m, s, nn;
        String[] claim;
        int[] pub, dd;
        boolean[] nd, liar;
        double[] cx;
        List<int[]> pol = new ArrayList<>(); // {경찰주장자, 대상, 1=마피아}
        List<int[]> pv = new ArrayList<>();  // {투표한 사람, 대상}
        List<Rep> rp = new ArrayList<>();
        boolean mad;

        Facts copy() {
            Facts f = new Facts();
            f.ids = ids; f.idx = idx; f.m = m; f.s = s; f.nn = nn; f.mad = mad;
            f.items = new ArrayList<>(items);
            f.claim = claim.clone(); f.pub = pub.clone(); f.dd = dd.clone(); f.nd = nd.clone(); f.liar = liar.clone(); f.cx = cx.clone();
            f.pol = new ArrayList<>(pol); f.pv = new ArrayList<>(pv); f.rp = new ArrayList<>(rp);
            return f;
        }

        void alloc() {
            int n = ids.size();
            claim = new String[n]; pub = new int[n]; Arrays.fill(pub, -1); dd = new int[n];
            nd = new boolean[n]; liar = new boolean[n]; cx = new double[n];
        }

        Integer id(String s) { return s == null ? null : idx.get(s); }

        void apply(String item) {
            if (item.isEmpty()) return;
            String[] f = item.split("\\|", -1);
            Integer a = f.length > 1 ? idx.get(f[1]) : null;
            switch (f[0]) {
                case "cl": if (a != null && f.length > 2) claim[a] = f[2].isEmpty() || f[2].equals("-") ? null : f[2]; break;
                case "pub": if (a != null && f.length > 2) pub[a] = type(f[2]); break;
                case "nd": if (a != null) nd[a] = true; break;
                case "dd": if (a != null && f.length > 2) dd[a] = (int) num(f[2]); break;
                case "liar": if (a != null) liar[a] = true; break;
                case "cx": if (a != null && f.length > 2) cx[a] = num(f[2]); break;
                case "pol":
                    if (a != null && f.length > 3) {
                        Integer t = id(f[2]);
                        if (t != null) {
                            int r = f[3].equals("mafia") ? 1 : 0;
                            boolean dup = false;
                            for (int[] q : pol) if (q[0] == a && q[1] == t && q[2] == r) dup = true;
                            if (!dup) pol.add(new int[]{a, t, r});
                        }
                    }
                    break;
                case "pv":
                    if (a != null && f.length > 2) { Integer t = id(f[2]); if (t != null) pv.add(new int[]{a, t}); }
                    break;
                case "rp":
                    if (a != null && f.length > 5) {
                        Rep r = new Rep();
                        r.who = a; r.day = (int) num(f[2]); r.role = f[3];
                        Integer t = id(f[4]); r.tgt = t == null ? -1 : t;
                        r.res = f[5];
                        if (r.res.startsWith("tgt:")) { Integer y = id(r.res.substring(4)); r.y = y == null ? -1 : y; }
                        rp.add(r);
                    }
                    break;
                case "mad": mad = true; break;
                default: break;
            }
        }

        /** 그날 x 의 보고(없으면 null) */
        Rep repOf(int x, int day) {
            for (Rep r : rp) if (r.who == x && r.day == day) return r;
            return null;
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
        for (String it : items) { f.apply(it); f.items.add(it); }
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
        Map<String, List<Integer>> byRole = new HashMap<>();
        for (int i = 0; i < n; i++) if (f.claim[i] != null && UNIQUE.contains(f.claim[i])) byRole.computeIfAbsent(f.claim[i], k -> new ArrayList<>()).add(i);
        double[] cw = new double[n];
        for (int i = 0; i < n; i++) {
            double x = 1;
            if (f.liar[i]) x *= 0.02;
            if (f.cx[i] > 0) x *= Math.max(0.03, Math.exp(-f.cx[i] / 110.0));
            cw[i] = x;
        }
        int cTot = Math.max(1, n - f.m - f.s - f.nn);
        double lc = 1.0 / cTot;
        double[] ll = new double[n];
        for (int i = 0; i < n; i++) ll[i] = f.claim[i] == null ? 1 : ("police".equals(f.claim[i]) || "shaman".equals(f.claim[i]) ? 0.10 : 0.08);

        // 탐정 보고: (탐정, 대상) 쌍마다 "탐정이 C 이고 대상도 C 면 말이 안 맞는가"를 미리 계산
        List<int[]> detBad = new ArrayList<>(); // {탐정, 대상}
        for (Rep r : f.rp) {
            if (!"detective".equals(r.role) || r.tgt < 0 || r.tgt == r.who) continue;
            if (!"detective".equals(f.claim[r.who])) continue;
            int x = r.tgt;
            String xc = f.claim[x];
            Rep xr = f.repOf(x, r.day);
            boolean bad = false;
            if ("none".equals(r.res)) {
                if (xr != null && xr.tgt >= 0) bad = true;
            } else if (r.res != null && r.res.startsWith("tgt:")) {
                if (xc != null && !ACTS.contains(xc)) bad = true;
                else if (xr != null && xr.tgt != r.y) bad = true;
            }
            if (bad) detBad.add(new int[]{r.who, x});
        }
        // 의사·수호자 보고: C 라면 말이 안 맞는 보고
        List<int[]> protBad = new ArrayList<>(); // {말한 사람}
        for (Rep r : f.rp) {
            if (r.tgt < 0 || f.dd[r.tgt] != r.day || r.day <= 0) continue;
            if ("doctor".equals(r.role) && "doctor".equals(f.claim[r.who])) protBad.add(new int[]{r.who});
            if ("guardian".equals(r.role) && "guardian".equals(f.claim[r.who]) && r.tgt != r.who && f.dd[r.who] != r.day) protBad.add(new int[]{r.who});
        }

        for (int k = 0; k < count; k++) {
            int o = k * n;
            double x = 1;
            boolean polOk = false, docOk = false;
            for (int i = 0; i < n; i++) {
                int t = worlds[o + i];
                if (t == C) {
                    x *= cw[i];
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
                if (!"police".equals(f.claim[p[0]])) continue;
                if (worlds[o + p[0]] == C) {
                    boolean isM = worlds[o + p[1]] == M;
                    if ((p[2] == 1) != isM) x *= eps;
                } else {
                    x *= p[2] == 1 ? 0.2 : 0.8;
                }
            }
            for (int[] d : detBad) if (worlds[o + d[0]] == C && worlds[o + d[1]] == C) x *= eps;
            for (int[] d : protBad) if (worlds[o + d[0]] == C) x *= 0.15;
            for (int[] v : f.pv) {
                int a = worlds[o + v[0]], b = worlds[o + v[1]];
                if (b == M && a == M) x *= 0.45;
                else if (b == M && a == S) x *= 0.75;
            }
            w[k] = x;
        }
        return w;
    }

    // ---- 관점
    static final class Cons {
        int meI = -1, meT = -1; String myRole; Map<Integer, Character> k = new HashMap<>();
    }

    static Cons cons(Facts f, String me, String constraints) {
        Cons c = new Cons();
        c.meI = me == null || me.isEmpty() ? -1 : f.idx.getOrDefault(me, -1);
        for (String it : constraints.split(";")) {
            String[] p = it.split("\\|", -1);
            if (p[0].equals("me") && p.length > 1) c.meT = type(p[1]);
            else if (p[0].equals("role") && p.length > 1 && !p[1].isEmpty()) c.myRole = p[1];
            else if (p[0].equals("k") && p.length > 2) { Integer a = f.idx.get(p[1]); if (a != null && !p[2].isEmpty()) c.k.put(a, p[2].charAt(0)); }
        }
        return c;
    }

    /** 관점 c 에서의 확률 [종류][참가자]. 맞는 세계가 없으면 null */
    static double[][] compute(Facts f, double[] w, Cons c) {
        int n = f.ids.size();
        double eps = f.mad ? 0.15 : 0.04;
        double[][] r = new double[3][n];
        double tot = 0;
        for (int q = 0; q < count; q++) {
            int o = q * n;
            if (c.meI >= 0 && c.meT >= 0 && worlds[o + c.meI] != c.meT) continue;
            boolean ok = true;
            for (Map.Entry<Integer, Character> e : c.k.entrySet()) {
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
            double x = w[q];
            if (c.myRole != null && UNIQUE.contains(c.myRole) && c.meT == C) {
                for (int i = 0; i < n; i++) if (i != c.meI && c.myRole.equals(f.claim[i]) && worlds[o + i] == C) x *= eps;
            }
            if (x <= 0) continue;
            tot += x;
            for (int i = 0; i < n; i++) {
                int t = worlds[o + i];
                if (t < C) r[t][i] += x;
            }
        }
        if (tot <= 0) return null;
        for (int t = 0; t < 3; t++) for (int i = 0; i < n; i++) r[t][i] /= tot;
        return r;
    }

    /** 관점 하나(봇 id, 공개 관점은 "")의 확률을 계산해 둔다. 맞는 세계가 없으면 공개 관점 그대로 */
    public static synchronized boolean view(String key, String me, String constraints) {
        if (base == null) return false;
        double[][] r = compute(base, weight, cons(base, me, constraints));
        if (r == null) {
            if (!key.isEmpty() && views.containsKey("")) { views.put(key, views.get("")); return true; }
            return false;
        }
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
     * 근거 찾기: 관점(me, constraints)에서 id 의 "마피아 팀(M·S) 확률"을 가장 많이 올린 공개 사실 항목(최대 2개, "##"로 나눔).
     * 그 사실을 하나씩 빼 보고 확률이 얼마나 떨어지는지로 잰다. 뚜렷한 것(5%p 넘게)이 없으면 ""
     */
    public static synchronized String explain(String me, String constraints, String id) {
        if (base == null) return "";
        Integer ti = base.idx.get(id);
        if (ti == null) return "";
        Cons c = cons(base, me, constraints);
        double[][] r0 = compute(base, weight, c);
        if (r0 == null) return "";
        double p0 = r0[M][ti] + r0[S][ti];
        String best = "", second = "";
        double bd = 0.05, sd = 0.05;
        for (int i = 0; i < base.items.size(); i++) {
            String it = base.items.get(i);
            String head = it.split("\\|", 2)[0];
            if (!(head.equals("cl") || head.equals("nd") || head.equals("cx") || head.equals("liar") || head.equals("pol") || head.equals("rp") || head.equals("pv"))) continue;
            // 이 사람과 관련된 사실만 따진다(빠르게): 이 사람이 나오는 항목, 같은 직업을 주장한 사람의 주장·밤 사망
            boolean rel = it.contains(id);
            String tc = base.claim[ti];
            if (!rel && tc != null) {
                String[] p = it.split("\\|", -1);
                Integer a = p.length > 1 ? base.idx.get(p[1]) : null;
                if (a != null && tc.equals(base.claim[a]) && (head.equals("cl") || head.equals("nd"))) rel = true;
            }
            if (!rel) continue;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < base.items.size(); j++) if (j != i) sb.append(base.items.get(j)).append(';');
            Facts f = parse(sb.toString());
            double[][] r = compute(f, weigh(f), cons(f, me, constraints));
            if (r == null) continue;
            double d = p0 - (r[M][ti] + r[S][ti]);
            if (d > bd) { second = best; sd = bd; best = it; bd = d; }
            else if (d > sd) { second = it; sd = d; }
        }
        if (best.isEmpty()) return "";
        return second.isEmpty() ? best : best + "##" + second;
    }

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
            if (!Arrays.equals(f.pub, base.pub)) { build(f); rebuilt = true; }
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

    // ---- 시민 쪽 미리 따져 보기

    static double hb(double p) {
        if (p <= 1e-9 || p >= 1 - 1e-9) return 0;
        return -(p * Math.log(p) + (1 - p) * Math.log(1 - p));
    }

    static int[] idsOf(Facts f, String csv) {
        List<Integer> l = new ArrayList<>();
        for (String s : csv.split(",")) { Integer i = f.idx.get(s.trim()); if (i != null) l.add(i); }
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    static double entropy(double[][] r, int[] alive) {
        double h = 0;
        for (int i : alive) h += hb(r[M][i]);
        return h;
    }

    /**
     * 조사 가치: 관점(me, constraints)에서 x 를 조사하면 살아 있는 사람들(alive, 쉼표)의 "마피아인가" 불확실성(엔트로피 합)이 평균 얼마나 줄어드나.
     * kind "M" = 경찰(마피아인지 아닌지 알게 됨), "C" = 탐정(대략 시민 팀인지 아닌지 알게 됨). 계산할 수 없으면 -1
     */
    public static synchronized double probe(String me, String constraints, String alive, String x, String kind) {
        if (base == null) return -1;
        Integer xi = base.idx.get(x);
        if (xi == null) return -1;
        Cons c = cons(base, me, constraints);
        double[][] r0 = compute(base, weight, c);
        if (r0 == null) return -1;
        int[] al = idsOf(base, alive);
        double h0 = entropy(r0, al);
        boolean pol = !"C".equals(kind);
        double pPos = pol ? r0[M][xi] : (r0[M][xi] + r0[S][xi] + r0[N][xi]);
        Cons cp = cons(base, me, constraints + ";k|" + x + "|" + (pol ? "M" : "E"));
        Cons cn = cons(base, me, constraints + ";k|" + x + "|" + (pol ? "T" : "C"));
        double[][] rp = pPos > 1e-6 ? compute(base, weight, cp) : null;
        double[][] rn = pPos < 1 - 1e-6 ? compute(base, weight, cn) : null;
        double h1 = 0;
        if (rp != null) h1 += pPos * entropy(rp, al); else if (pPos > 1e-6) h1 += pPos * h0;
        if (rn != null) h1 += (1 - pPos) * entropy(rn, al); else if (pPos < 1 - 1e-6) h1 += (1 - pPos) * h0;
        return h0 - h1;
    }

    // ---- 막판 계산: 생존자가 적을 때 "오늘 x 를 처형하면 시민 팀이 이길 확률"
    //   규칙(mvote.sk mWinBase 를 줄인 것): 마피아(M)가 모두 죽으면 시민 승(중립이 남아 있으면 반반으로 친다),
    //   중립이 없고 마피아 팀(M+S) 수 ≥ 시민 팀 수면 마피아 승. 밤에는 마피아가 시민 팀 중 한 명을 고르게 죽인다(마피아가 없으면 밤 사망 없음).
    //   앞으로의 낮에는 시민 쪽이 지금 관점에서 마피아 확률이 가장 높은 사람을 처형한다고 본다(믿음은 그대로, 대략).

    static byte[] egT;
    static double[] egPm;
    static int egN;

    static double egTerm(int mask) {
        int m = 0, s = 0, nn = 0, c = 0;
        for (int i = 0; i < egN; i++) {
            if ((mask & (1 << i)) == 0) continue;
            switch (egT[i]) { case M: m++; break; case S: s++; break; case N: nn++; break; default: c++; }
        }
        if (m == 0) return nn == 0 ? 1 : 0.5;
        if (nn == 0 && m + s >= c) return 0;
        if (m + s + nn + c <= 1) return 0.5;
        return -1;
    }

    static double egNight(int mask, int depth) {
        double t = egTerm(mask);
        if (t >= 0) return t;
        if (depth > 8) return 0.5;
        boolean anyM = false;
        List<Integer> vic = new ArrayList<>();
        for (int i = 0; i < egN; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (egT[i] == M) anyM = true;
            else if (egT[i] == C) vic.add(i);
        }
        if (!anyM || vic.isEmpty()) return egDay(mask, depth + 1);
        double v = 0;
        for (int i : vic) v += egDay(mask & ~(1 << i), depth + 1);
        return v / vic.size();
    }

    static double egDay(int mask, int depth) {
        double t = egTerm(mask);
        if (t >= 0) return t;
        if (depth > 8) return 0.5;
        int y = -1;
        double bp = -1;
        for (int i = 0; i < egN; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (egPm[i] > bp) { bp = egPm[i]; y = i; }
        }
        if (y < 0) return 0.5;
        return egNight(mask & ~(1 << y), depth);
    }

    /** 관점(me, constraints)에서 오늘 x 를 처형하면 시민 팀이 이길 확률(alive: 살아 있는 사람, 쉼표). 계산할 수 없으면 -1 */
    public static synchronized double endgame(String me, String constraints, String alive, String x) {
        if (base == null) return -1;
        Integer xi = base.idx.get(x);
        if (xi == null) return -1;
        int n = base.ids.size();
        if (n > 30) return -1;
        Cons c = cons(base, me, constraints);
        double[][] r = compute(base, weight, c);
        if (r == null) return -1;
        int[] al = idsOf(base, alive);
        int mask = 0;
        for (int i : al) mask |= 1 << i;
        if ((mask & (1 << xi)) == 0) return -1;
        egN = n;
        egPm = new double[n];
        for (int i = 0; i < n; i++) egPm[i] = (c.meI == i) ? -1 : r[M][i];
        egT = new byte[n];
        double tot = 0, win = 0;
        double eps = base.mad ? 0.15 : 0.04;
        for (int q = 0; q < count; q++) {
            double w = viewWeight(c, q, eps);
            if (w <= 0) continue;
            System.arraycopy(worlds, q * n, egT, 0, n);
            double t0 = egTerm(mask & ~(1 << xi));
            double v = t0 >= 0 ? t0 : egNight(mask & ~(1 << xi), 0);
            tot += w;
            win += w * v;
        }
        if (tot <= 0) return -1;
        return win / tot;
    }

    /** 관점 c 에서 세계 q 의 가중치(맞지 않으면 0) */
    static double viewWeight(Cons c, int q, double eps) {
        Facts f = base;
        int n = f.ids.size();
        int o = q * n;
        if (c.meI >= 0 && c.meT >= 0 && worlds[o + c.meI] != c.meT) return 0;
        for (Map.Entry<Integer, Character> e : c.k.entrySet()) {
            int t = worlds[o + e.getKey()];
            boolean ok = true;
            switch (e.getValue()) {
                case 'M': ok = t == M; break;
                case 'T': ok = t != M; break;
                case 'E': ok = t != C; break;
                case 'X': ok = t == M || t == S; break;
                case 'C': ok = t == C; break;
                default: break;
            }
            if (!ok) return 0;
        }
        double x = weight[q];
        if (c.myRole != null && UNIQUE.contains(c.myRole) && c.meT == C) {
            for (int i = 0; i < n; i++) if (i != c.meI && c.myRole.equals(f.claim[i]) && worlds[o + i] == C) x *= eps;
        }
        return x;
    }

    /** 세계 수(확인용) */
    public static synchronized int size() { return count; }
}
