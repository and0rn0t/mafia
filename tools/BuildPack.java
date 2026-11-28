// 마피아 리소스팩(배경음) 만들기. JDK 11 이상에서 설치 없이 실행:
//   java tools/BuildPack.java <ogg 폴더> [저장소 폴더]
// ogg 폴더의 파일 이름 → 소리 키:
//   낮1.ogg ~ 낮9.ogg (또는 day1.ogg ~)  → day1 ~ day9   (낮 BGM, 낮마다 무작위 하나)
//   투표.ogg (또는 vote.ogg)               → vote          (투표 BGM)
//   밤.ogg / 귀뚜라미.ogg (또는 night.ogg) → night         (밤 귀뚜라미 배경음)
// 만드는 것(저장소 폴더):
//   mafia-pack.zip   리소스팩(pack.mcmeta, assets/mafia/sounds.json, assets/mafia/sounds/<키>.ogg)
//   maudio_data.sk   있는 소리 키, 각 길이(초), 팩 SHA-1 (maudio.sk 가 읽는다)
// ogg 는 Vorbis 형식이어야 한다(마인크래프트 규칙). 배경음은 스테레오로 두면 위치와 상관없이 들린다.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public class BuildPack {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("사용법: java tools/BuildPack.java <ogg 폴더> [저장소 폴더]");
            System.exit(1);
        }
        Path src = Paths.get(args[0]);
        Path repo = Paths.get(args.length > 1 ? args[1] : ".");
        TreeMap<String, Path> files = new TreeMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src, "*.ogg")) {
            for (Path p : ds) {
                String key = keyOf(p.getFileName().toString());
                if (key == null) {
                    System.out.println("건너뜀(이름을 모름): " + p.getFileName());
                    continue;
                }
                files.put(key, p);
            }
        }
        if (files.isEmpty()) {
            System.out.println("ogg 파일이 없습니다: " + src.toAbsolutePath());
            System.exit(1);
        }
        TreeMap<String, Integer> lens = new TreeMap<>();
        for (Map.Entry<String, Path> e : files.entrySet()) {
            byte[] b = Files.readAllBytes(e.getValue());
            double sec = oggSeconds(b);
            if (sec <= 0) {
                System.out.println("ogg Vorbis 로 읽을 수 없습니다: " + e.getValue().getFileName());
                System.exit(1);
            }
            lens.put(e.getKey(), (int) Math.ceil(sec));
            System.out.printf("%-6s %4d초  %s%n", e.getKey(), (int) Math.ceil(sec), e.getValue().getFileName());
        }
        Path zip = repo.resolve("mafia-pack.zip");
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(zo, "pack.mcmeta", "{\n  \"pack\": {\n    \"description\": \"Mafia game sounds\",\n    \"pack_format\": 75,\n    \"min_format\": 75,\n    \"max_format\": 75\n  }\n}\n".getBytes(StandardCharsets.UTF_8));
            StringBuilder sj = new StringBuilder("{\n");
            int i = 0;
            for (String k : files.keySet()) {
                sj.append("  \"bgm.").append(k).append("\": {\"sounds\": [{\"name\": \"mafia:").append(k).append("\", \"stream\": true}]}");
                sj.append(++i < files.size() ? ",\n" : "\n");
            }
            sj.append("}\n");
            put(zo, "assets/mafia/sounds.json", sj.toString().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, Path> e : files.entrySet()) {
                put(zo, "assets/mafia/sounds/" + e.getKey() + ".ogg", Files.readAllBytes(e.getValue()));
            }
            // PVP(MCK 전투) 리소스(pvp-pack.zip, tools/MergePvpPack.java)가 있으면 같이 넣는다
            Path pvp = repo.resolve("pvp-pack.zip");
            if (Files.exists(pvp)) {
                try (ZipInputStream zi = new ZipInputStream(Files.newInputStream(pvp))) {
                    ZipEntry ze;
                    while ((ze = zi.getNextEntry()) != null) {
                        if (!ze.isDirectory()) put(zo, ze.getName(), zi.readAllBytes());
                    }
                }
            }
        }
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        String sha = HexFormat.of().formatHex(md.digest(Files.readAllBytes(zip)));
        StringBuilder sk = new StringBuilder();
        sk.append("# [maudio_data.sk] tools/BuildPack.java 가 만든 파일(손으로 고치지 말 것). 리소스팩 소리 키, 길이(초), 팩 SHA-1.\r\n\r\n");
        sk.append("function mAuKeys() :: texts:\r\n\treturn ");
        StringJoiner kj = new StringJoiner(", ");
        for (String k : files.keySet()) kj.add("\"" + k + "\"");
        sk.append(kj).append("\r\n\r\n");
        sk.append("function mAuLen(k: text) :: number:\r\n");
        for (Map.Entry<String, Integer> e : lens.entrySet()) {
            sk.append("\tif {_k} is \"").append(e.getKey()).append("\":\r\n\t\treturn ").append(e.getValue()).append("\r\n");
        }
        sk.append("\treturn 0\r\n\r\n");
        sk.append("function mAuSha1() :: text:\r\n\treturn \"").append(sha).append("\"\r\n");
        Files.write(repo.resolve("maudio_data.sk"), sk.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("만듦: " + zip.toAbsolutePath() + " (SHA-1 " + sha + ")");
        System.out.println("만듦: " + repo.resolve("maudio_data.sk").toAbsolutePath());
    }

    static String keyOf(String name) {
        String n = name.substring(0, name.length() - 4).trim().toLowerCase(Locale.ROOT);
        if (n.equals("투표") || n.equals("vote")) return "vote";
        if (n.equals("밤") || n.equals("귀뚜라미") || n.equals("night")) return "night";
        for (String pre : new String[]{"낮", "day"}) {
            if (n.startsWith(pre)) {
                String rest = n.substring(pre.length()).trim();
                if (rest.matches("[1-9]")) return "day" + rest;
            }
        }
        return null;
    }

    static void put(ZipOutputStream zo, String name, byte[] data) throws IOException {
        zo.putNextEntry(new ZipEntry(name));
        zo.write(data);
        zo.closeEntry();
    }

    // Vorbis 식별 헤더의 표본 빈도와 마지막 Ogg 페이지의 granule 위치로 길이를 구한다
    static double oggSeconds(byte[] b) {
        byte[] sig = {1, 'v', 'o', 'r', 'b', 'i', 's'};
        int at = indexOf(b, sig, 0);
        if (at < 0 || at + 16 > b.length) return -1;
        long rate = le(b, at + 12, 4);
        if (rate <= 0) return -1;
        for (int i = b.length - 27; i >= 0; i--) {
            if (b[i] == 'O' && b[i + 1] == 'g' && b[i + 2] == 'g' && b[i + 3] == 'S') {
                long g = le(b, i + 6, 8);
                if (g > 0) return (double) g / rate;
            }
        }
        return -1;
    }

    static int indexOf(byte[] b, byte[] s, int from) {
        outer:
        for (int i = from; i + s.length <= b.length; i++) {
            for (int j = 0; j < s.length; j++) if (b[i + j] != s[j]) continue outer;
            return i;
        }
        return -1;
    }

    static long le(byte[] b, int off, int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }
}
