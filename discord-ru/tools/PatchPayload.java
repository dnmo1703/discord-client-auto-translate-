import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;
import com.reandroid.dex.key.KeyPair;
import com.reandroid.dex.key.MethodKey;
import com.reandroid.dex.model.DexInstruction;
import com.reandroid.dex.key.TypeKey;
import com.reandroid.dex.model.DexDirectory;
import com.reandroid.dex.model.DexFile;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PatchPayload {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[2]);
        try (ZipFile host = new ZipFile(args[0]); ZipFile payload = new ZipFile(args[1])) {
            Set<TypeKey> hostTypes = new HashSet<>();
            for (ZipEntry entry : dexEntries(host)) {
                try (InputStream in = host.getInputStream(entry)) {
                    DexFile dex = DexFile.read(in);
                    dex.getDexClasses().forEachRemaining(cls -> hostTypes.add(cls.getKey()));
                    if ("classes.dex".equals(entry.getName())) {
                        patchMessageHolder(dex);
                        Path patched = output.resolve(entry.getName());
                        dex.write(patched.toFile());
                        DexFile.read(patched.toFile());
                    }
                }
            }

            List<ZipEntry> entries = dexEntries(payload);
            if (entries.size() != 1) {
                throw new IllegalArgumentException("Expected one DEX file in the patch payload");
            }
            DexDirectory directory = new DexDirectory();
            Path inputDex = output.resolve("payload-original.dex");
            try (InputStream in = payload.getInputStream(entries.get(0))) {
                Files.copy(in, inputDex, StandardCopyOption.REPLACE_EXISTING);
            }
            directory.addFile(inputDex.toFile());
            Map<String, String> renamed = new LinkedHashMap<>();
            List<KeyPair<TypeKey, TypeKey>> changes = new ArrayList<>();
            // Both APKs contain a compatibility stub for this Android platform type.
            directory.removeClass(TypeKey.of("Landroid/app/AppComponentFactory;"));
            directory.getDexClasses().forEachRemaining(cls -> {
                TypeKey original = cls.getKey();
                if (!hostTypes.contains(original)) return;
                String descriptor = original.toString();
                TypeKey replacement = TypeKey.of("Ldev/discordru/shaded/" + descriptor.substring(1));
                changes.add(new KeyPair<>(original, replacement));
                renamed.put(javaName(descriptor), javaName(replacement.toString()));
            });
            var changed = directory.renameTypes(changes, true, true);
            while (changed.hasNext()) changed.next();
            directory.refreshFull();
            DexFile code = directory.getDexClasses().next().getDexFile();
            Path outputDex = output.resolve("payload.dex");
            code.write(outputDex.toFile());
            DexFile.read(outputDex.toFile());
            code.getDexClasses().forEachRemaining(cls -> {
                if (hostTypes.contains(cls.getKey())) {
                    throw new IllegalStateException("Duplicate class " + cls.getKey());
                }
            });

            AndroidManifestBlock hostManifest = manifest(host);
            AndroidManifestBlock payloadManifest = manifest(payload);
            patchManifest(hostManifest, payloadManifest, renamed);
            hostManifest.writeBytes(output.resolve("AndroidManifest.xml").toFile());

            TableBlock hostTable = resources(host);
            TableBlock payloadTable = resources(payload);
            if (hostTable.getPackageBlockById(0x78) != null
                    || payloadTable.getPackageBlockById(0x78) == null) {
                throw new IllegalArgumentException("Expected an unused resource package ID 0x78");
            }
            payloadTable.getStringPool().iterator().forEachRemaining(item -> {
                String value = item.get();
                if (value != null && value.startsWith("res/") && payload.getEntry(value) != null) {
                    item.set("res/discordru/" + value.substring(4));
                }
            });
            hostTable.merge(payloadTable);
            hostTable.refreshFull();
            hostTable.writeBytes(output.resolve("resources.arsc").toFile());
        }
    }

    private static void patchMessageHolder(DexFile dex) throws Exception {
        var holder = dex.getDexClass(TypeKey.of("Ltc/s0;"));
        var constructor = holder.getDeclaredMethod(MethodKey.parse(
                "Ltc/s0;-><init>(Lcom/discord/chat/presentation/message/view/MessageContentView;)V"));
        var callSuper = constructor.getInstruction(0);
        if (constructor.getInstructionsCount() != 3
                || !MethodKey.parse("Ltc/t0;-><init>(Landroid/view/View;)V").equals(callSuper.getKey())) {
            throw new IllegalStateException("Unexpected Discord message holder constructor");
        }
        constructor.ensureLocalRegistersCount(1);
        callSuper = constructor.getInstruction(0);
        callSuper.replaceFromSmaliAll("""
                invoke-static {p1}, Ldev/discordru/InlineTranslation;->wrap(Landroid/widget/TextView;)Landroid/view/View;
                move-result-object v0
                invoke-direct {p0, v0}, Ltc/t0;-><init>(Landroid/view/View;)V
                """);

        var binders = holder.getDeclaredMethods(method -> method.getName().equals("v")
                && method.getKey().getParameter(0).equals(TypeKey.of("Lbc/g0;")));
        if (!binders.hasNext()) throw new IllegalStateException("Discord message binder is missing");
        var binder = binders.next();
        var renderCalls = binder.getInstructionsIfMethodKey(key -> key.getName().equals("d")
                && key.getDeclaring().equals(
                        TypeKey.of("Lcom/discord/chat/presentation/message/view/MessageContentView;")));
        if (!renderCalls.hasNext()) throw new IllegalStateException("Discord message renderer is missing");
        DexInstruction render = renderCalls.next();
        if (binders.hasNext() || renderCalls.hasNext() || render.getRegister() != 1
                || !render.getNext().getOpcode().getName().equals("return-void")) {
            throw new IllegalStateException("Unexpected Discord message binder");
        }
        render.createNextFromSmaliAll("""
                move-object/from16 v2, p1
                iget-object v2, v2, Lbc/g0;->d:Ljava/lang/String;
                invoke-static {v1, v2}, Ldev/discordru/InlineTranslation;->bind(Landroid/widget/TextView;Ljava/lang/String;)V
                """);
        dex.refreshFull();
    }

    private static List<ZipEntry> dexEntries(ZipFile apk) {
        return apk.stream().filter(entry -> entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))
                .map(entry -> (ZipEntry) entry).toList();
    }

    private static String javaName(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static AndroidManifestBlock manifest(ZipFile apk) throws Exception {
        try (InputStream input = apk.getInputStream(apk.getEntry("AndroidManifest.xml"))) {
            return AndroidManifestBlock.load(input);
        }
    }

    private static TableBlock resources(ZipFile apk) throws Exception {
        try (InputStream input = apk.getInputStream(apk.getEntry("resources.arsc"))) {
            return TableBlock.load(input);
        }
    }

    private static void patchManifest(AndroidManifestBlock host, AndroidManifestBlock payload,
                                      Map<String, String> renamed) {
        if (!"com.discord".equals(host.getPackageName()) || host.getVersionCode() != 355010) {
            throw new IllegalArgumentException("Expected Discord Android build 355010");
        }
        ResXmlElement app = host.getApplicationElement();
        for (String type : List.of("activity", "provider", "service", "receiver")) {
            for (ResXmlElement component : payload.listApplicationElementsByTag(type)) {
                String name = AndroidManifestBlock.getAndroidNameValue(component);
                if (name.equals("androidx.startup.InitializationProvider")) continue;
                if (type.equals("activity") && !name.equals("dev.discordru.ProxyActivity")) continue;
                ResXmlElement added = app.newElement(type);
                added.fromJson(component.toJson());
                added.recursiveAttributes().forEachRemaining(attribute -> {
                    if (attribute.getValueType() != ValueType.STRING) return;
                    String value = attribute.getValueAsString();
                    if (renamed.containsKey(value)) {
                        attribute.setValueAsString(renamed.get(value));
                    } else if (value.startsWith("backend:") && renamed.containsKey(value.substring(8))) {
                        attribute.setValueAsString("backend:" + renamed.get(value.substring(8)));
                    }
                });
                if ("dev.discordru.StartupProvider".equals(name)) {
                    added.getOrCreateAndroidAttribute("initOrder", 0x0101001a).setValueAsDecimal(1000);
                }
            }
        }
        for (String permission : payload.getUsesPermissions()) {
            if (permission.startsWith("android.permission.") && host.getUsesPermission(permission) == null) {
                host.addUsesPermission(permission);
            }
        }
        host.refreshFull();
    }
}
