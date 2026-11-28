// PVP(MCK 전투) 리소스팩을 마피아 리소스팩에 합치기. JDK 11 이상에서 설치 없이 실행:
//   java tools/MergePvpPack.java [mck.zip] [저장소 폴더]
// 1) mck.zip 을 주면(andornot 저장소의 resourcepack/mck.zip) 거기서 PVP 에 필요한 것만 골라 pvp-pack.zip 을 새로 만든다.
//    빼는 것: pack.mcmeta, pack.png, 글꼴 설명 파일, 마인크래프트 기본 글꼴 덮어쓰기(assets/minecraft/font, assets/minecraft/textures/font)
//    — 기본 글꼴을 바꾸면 마피아 게임 글자 모양까지 바뀌므로 넣지 않는다. 배고픔 아이콘(⚡, 스태미너 표시)은 넣는다(마피아 쪽 배고픔 칸도 ⚡ 로 보인다).
// 2) mafia-pack.zip 에 pvp-pack.zip 의 파일을 합치고(같은 이름은 pvp-pack 이 덮음, 마피아 소리·pack.mcmeta 는 그대로)
//    maudio_data.sk 의 mAuSha1() 을 새 팩의 SHA-1 로 바꾼다.
// BuildPack.java(배경음 다시 만들기)도 pvp-pack.zip 이 있으면 같이 넣는다.
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public class MergePvpPack {
    public static void main(String[] args) throws Exception {
        Path repo = Paths.get(args.length > 1 ? args[1] : ".");
        Path pvp = repo.resolve("pvp-pack.zip");
        if (args.length > 0) {
            LinkedHashMap<String, byte[]> keep = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : read(Paths.get(args[0])).entrySet()) {
                if (wanted(e.getKey())) keep.put(e.getKey(), e.getValue());
            }
            write(pvp, keep);
            System.out.println("만듦: " + pvp.toAbsolutePath() + " (" + keep.size() + "개)");
        }
        if (!Files.exists(pvp)) {
            System.out.println("pvp-pack.zip 이 없습니다. 먼저 mck.zip 경로를 주세요.");
            System.exit(1);
        }
        Path zip = repo.resolve("mafia-pack.zip");
        LinkedHashMap<String, byte[]> all = read(zip);
        all.putAll(read(pvp));
        write(zip, all);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(zip)));
        Path data = repo.resolve("maudio_data.sk");
        String sk = new String(Files.readAllBytes(data), StandardCharsets.UTF_8);
        sk = sk.replaceAll("(function mAuSha1\\(\\) :: text:\\r?\\n\\treturn \")[0-9a-f]+(\")", "$1" + sha + "$2");
        Files.write(data, sk.getBytes(StandardCharsets.UTF_8));
        System.out.println("합침: " + zip.toAbsolutePath() + " (" + all.size() + "개, SHA-1 " + sha + ")");
    }

    // PVP 에 넣을 파일인지
    static boolean wanted(String n) {
        if (n.endsWith("/")) return false;
        if (n.equals("pack.mcmeta") || n.equals("pack.png")) return false;
        if (n.startsWith("LICENSE_font") || n.startsWith("README_font")) return false;
        if (n.startsWith("assets/minecraft/font/")) return false;
        if (n.startsWith("assets/minecraft/textures/font/")) return false;
        return true;
    }

    static LinkedHashMap<String, byte[]> read(Path p) throws IOException {
        LinkedHashMap<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream zi = new ZipInputStream(Files.newInputStream(p))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                if (!e.isDirectory()) m.put(e.getName(), zi.readAllBytes());
            }
        }
        return m;
    }

    static void write(Path p, Map<String, byte[]> m) throws IOException {
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(p))) {
            for (Map.Entry<String, byte[]> e : m.entrySet()) {
                zo.putNextEntry(new ZipEntry(e.getKey()));
                zo.write(e.getValue());
                zo.closeEntry();
            }
        }
    }
}
