package de.bhurling.lyml;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class YmlParser {
    public Map<String, Map<String, String>> mTranslations = new HashMap<>();

    private final String[] mApiKeys;

    private Object mDefaultLocale;

    private File mOutputDirectory;

    public YmlParser(String... keys) {
        mApiKeys = keys;
    }

    public void parseAndStore(InputStream is) {
        Yaml yaml = new Yaml();

        Map<?, ?> map = (Map<?, ?>) yaml.load(is);

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String locale = entry.getKey().toString();
            if (mDefaultLocale == null) {
                mDefaultLocale = locale;
            }
            Map<String, String> output = mTranslations.get(locale);
            if (output == null) {
                output = new HashMap<>();
            }
            parseAndStore("", (Map<?, ?>) entry.getValue(), output);
            mTranslations.put(locale, output);
        }
    }

    public void parseAndStore(String prefix, Map<?, ?> map, Map<String, String> output) {
        if (map == null || map.isEmpty())
            return;

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String nextKey = entry.getKey().toString();
            String nextPrefix = StringUtils.isEmpty(prefix) ?
                    nextKey : String.format("%s.%s", prefix, nextKey);
            Object nextValue = entry.getValue();
            if (nextValue instanceof Map) {
                parseAndStore(nextPrefix, (Map<?, ?>) nextValue, output);
            } else if (!StringUtils.isEmpty((String) nextValue)) {
                // Keep existing translations
                if (!output.containsKey(nextPrefix)) {
                    output.put(nextPrefix, (String) nextValue);
                }
            }
        }
    }

    public void createResources(boolean android, boolean ios, boolean windows, boolean java) throws IOException {
        if (mDefaultLocale == null) {
            System.out.println("Failed to load translations.");
            return;
        }

        // Fetch a list of (alphabetically sorted) keys from the default locale's set of translations
        ArrayList<String> keys = new ArrayList<>(mTranslations.get(mDefaultLocale.toString()).keySet());
        Collections.sort(keys);

        if (android) {
            createAndroidResources(keys);
        }

        if (ios) {
            createIosResources(keys);
        }

        if (windows) {
            createWinPhoneResources(keys);
        }

        if (java) {
            createJavaResources(keys);
        }

        System.out.println("\nDone. Have a look into\n\n" + mOutputDirectory.getAbsolutePath()
                + "\n\nto find your files. Have a nice day!\n\n");
    }


    private void createJavaResources(ArrayList<String> keys) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream("out/strings-java.zip"));

        System.out.println("Creating Java resources.");

        for (String locale : mTranslations.keySet()) {

            String[] splittedLocale = locale.split("-");

            String languageCode = splittedLocale[0];
            String regionalCode = splittedLocale.length == 2 ? splittedLocale[1] : null;

            if (!mDefaultLocale.equals(locale)) {

                if (regionalCode != null) {
                    languageCode += "-" + regionalCode.toUpperCase();
                }
            }
            String outPath = String.format("%s", languageCode);

            // append the filename (based on ZIPs filename)
            String nextOutEntry = String.format("%s/language.properties", outPath);
            zos.putNextEntry(new ZipEntry(nextOutEntry));

            for (String key : keys) {
                // find a value in the regional translations
                String value = getValue(key, locale);

                // create an android resource string and write to the output zip
                if (!StringUtils.isEmpty(value)) {
                    zos.write(createJavaResource(key, value).getBytes("UTF-8"));
                }
            }
        }

        zos.close();

    }

    private void createAndroidResources(ArrayList<String> keys) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream("out/strings-android.zip"));

        System.out.println("Creating Android resources.");

        for (String locale : mTranslations.keySet()) {

            String[] splittedLocale = locale.split("-");

            String languageCode = splittedLocale[0];
            String regionalCode = splittedLocale.length == 2 ? splittedLocale[1] : null;

            // append values-xx-rXX (but leave out xx if this is the default locale)
            String appendix = "";
            if (!mDefaultLocale.equals(locale)) {
                appendix = "-" + languageCode;

                if (regionalCode != null) {
                    appendix += "-r" + regionalCode.toUpperCase();
                }
            }
            String outPath = String.format("values%s", appendix);

            // append the filename (based on ZIPs filename)
            String nextOutEntry = String.format("%s/strings.xml", outPath);
            zos.putNextEntry(new ZipEntry(nextOutEntry));

            zos.write(AndroidResource.OPEN.getBytes("UTF-8"));

            for (String key : keys) {
                // find a value in the regional translations
                String value = getValue(key, locale);

                // create an android resource string and write to the output zip
                if (!StringUtils.isEmpty(value)) {
                    zos.write(createAndroidResource(key, value).getBytes("UTF-8"));
                }
            }

            zos.write(AndroidResource.CLOSE.getBytes("UTF-8"));
        }

        zos.close();
    }

    private void createIosResources(ArrayList<String> keys) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream("out/strings-ios.zip"));

        System.out.println("Creating iOS resources.");

        for (String locale : mTranslations.keySet()) {

            // Create next zip entry (e.g. en-US.lproj/Localizable.strings)
            String nextOutEntry = String.format("%s.lproj/Localizable.strings", fixLocaleForIOS(locale));
            zos.putNextEntry(new ZipEntry(nextOutEntry));

            for (String key : keys) {

                // find a value in the translations for the complete locale
                String value = getValue(key, locale);

                // if none is found, find a value in the base language
                if (StringUtils.isEmpty(value)) {
                    value = getValue(key, locale.split("-")[0]);
                }

                // if still empty, use the default locale
                if (StringUtils.isEmpty(value)) {
                    value = getValue(key, mDefaultLocale.toString());
                }

                // create an ios resource string and write to the output zip
                if (!StringUtils.isEmpty(value)) {
                    zos.write(createLocalizableString(key, value).getBytes("UTF-8"));
                }
            }
        }

        // Create constant mapping
        // OBJ-C
        zos.putNextEntry(new ZipEntry("StringDefinitions.h"));

        for (String key : keys) {
            zos.write(createIosObjCDefinition(key).getBytes("UTF-8"));
        }

        // Swift
        zos.putNextEntry(new ZipEntry("StringDefinitions.swift"));

        zos.write("struct L {\n".getBytes("UTF-8"));
        for (String key : keys) {
            zos.write(("    " + createIosSwiftDefinition(key)).getBytes("UTF-8"));
        }
        zos.write("}".getBytes("UTF-8"));

        zos.close();
    }

    private void createWinPhoneResources(ArrayList<String> keys) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream("out/strings-win.zip"));

        System.out.println("Creating Windows Phone resources.");

        for (String locale : mTranslations.keySet()) {
            String nextOutEntry = String.format("Strings/%s/Resources.resw", locale);
            zos.putNextEntry(new ZipEntry(nextOutEntry));
            zos.write(WinPhoneResource.OPEN.getBytes("UTF-8"));

            for (String key : keys) {

                String value = getValue(key, locale);

                if (!StringUtils.isEmpty(value)) {
                    zos.write(createWinPhoneResource(key, value).getBytes("UTF-8"));
                }
            }

            zos.write(WinPhoneResource.CLOSE.getBytes("UTF-8"));
        }

        zos.close();
    }

    public String createWinPhoneResource(String key, String value) {
        return String.format("" +
                        "\t<data name=\"%s\">\n" +
                        "\t\t<value>%s</value>\n" +
                        "\t</data>\n",
                key.replace(".", "_"),
                fixValueForWinPhone(value));
    }

    public String fixLocaleForIOS(String locale) {
        return locale.replace("zh", "zh-Hans");
    }

    public String createLocalizableString(String key, String value) {
        return String.format("\"%s\"=\"%s\";\n",
                fixKeyForIOS(key),
                fixValueForIOS(value));
    }

    public String createIosObjCDefinition(String key) {
        return String.format("#define k%s @\"%s\"\n",
                camelCase(key),
                fixKeyForIOS(key));
    }

    public String createIosSwiftDefinition(String key) {
        return String.format("static let %s = \"%s\"\n",
                StringUtils.uncapitalize(camelCase(key)),
                fixKeyForIOS(key));
    }

    public String createAndroidResource(String key, String value) {
        return String.format("    <string name=\"%s\">%s</string>\n",
                key.replace(".", "_"),
                fixValueForAndroid(value));
    }

    public String createJavaResource(String key, String value) {
        return String.format("%s=%s\n", key, value);
    }

    private String getValue(String key, String languageCode) {
        Map<String, String> map = mTranslations.get(languageCode);
        if (map == null) {
            return null;
        }

        return map.get(key);
    }

    public String fixValueForAndroid(String nextValue) {
        return nextValue.replace("\r\n", "\\n").replace("\n\r", "\\n")
                .replace("\r", "\\n").replace("\n", "\\n")
                .replace("&", "&amp;")
                .replace("...", "…")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "\\'")
                .replaceAll("%(\\S*)@", "%$1s")
                .replaceAll("%(?!\\S*[dfs])", "%%");
    }

    public String fixKeyForIOS(String key) {
        return key.replace(".", "_");
    }

    public String fixValueForIOS(String value) {
        String fixed = value.replace("%s", "%@");

        fixed = fixed.replaceAll("%(\\d+\\$)s", "%$1@").replace("\"", "\\\"");

        return fixed;
    }

    public String fixValueForWinPhone(String value) {
        String fixed = value
                .replace("\\n", "\n")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("%s", "{0}");

        Pattern pattern = Pattern.compile("%((\\d+)\\$)s");
        Matcher matcher = pattern.matcher(fixed);
        while (matcher.find()) {
            int placeholderInt = Integer.parseInt(matcher.group(2)) - 1;
            fixed = fixed.replace(matcher.group(), "{" + placeholderInt + "}");
        }

        return fixed;
    }

    public String camelCase(String string) {
        return StringUtils.join((WordUtils.capitalizeFully(string
                .replace(".", " ").replace("_", " "))).split(" "), "");
    }

    public void fetchFromLocaleApp() {
        try {
            mOutputDirectory = new File("out");
            mOutputDirectory.mkdirs();

            ZipOutputStream zos = new ZipOutputStream(
                    new FileOutputStream(new File(mOutputDirectory, "original-yml.zip")));

            for (String apiKey : mApiKeys) {
                System.out.println("Fetching translations for project " + apiKey);
                String url = String.format(
                        "https://api.localeapp.com/v1/projects/%s/translations/all.yml", apiKey);
                InputStream is = fetchTranslations(url);

                if (is != null) {
                    parseAndStore(is);
                }

                is = fetchTranslations(url);

                if (is != null) {
                    String nextOutEntry = String.format("%s.yml", apiKey);
                    zos.putNextEntry(new ZipEntry(nextOutEntry));

                    byte[] dataBlock = new byte[4096];
                    int count = is.read(dataBlock, 0, 4096);
                    while (count != -1) {
                        zos.write(dataBlock, 0, count);
                        count = is.read(dataBlock, 0, 4096);
                    }
                }

                System.out.println("Done.");
            }

            zos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private InputStream fetchTranslations(String url) {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try {
            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                return response.body().byteStream();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

}
