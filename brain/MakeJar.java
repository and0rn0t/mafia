// 폴더를 jar(zip)로 묶는다(jar 명령이 없는 JRE 용): java MakeJar.java <폴더> <jar>
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class MakeJar {
    public static void main(String[] a) throws Exception {
        Path root = Paths.get(a[0]);
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(Paths.get(a[1])));
             java.util.stream.Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted().toList()) {
                if (Files.isDirectory(p)) continue;
                zo.putNextEntry(new ZipEntry(root.relativize(p).toString().replace(File.separatorChar, '/')));
                zo.write(Files.readAllBytes(p));
                zo.closeEntry();
            }
        }
    }
}
