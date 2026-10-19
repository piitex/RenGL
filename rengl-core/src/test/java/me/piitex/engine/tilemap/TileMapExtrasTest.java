package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.graphics.BlendMode;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.text.TextAlign;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the tile map reader added beyond the basics: zstd layers, embedded images, layer blend modes, text objects and
 * the class defaults of a Tiled project.
 */
class TileMapExtrasTest {
    @TempDir
    Path dir;

    /** A 8x8 picture of four different 4x4 squares, as PNG bytes. */
    private static byte[] png() {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(8, 8, false);
            int[] colors = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00};
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) bitmap.erase(colors[(y / 4) * 2 + x / 4], org.jetbrains.skia.IRect.makeXYWH(x, y, 1, 1));
            try (Image image = Image.Companion.makeFromBitmap(bitmap)) {
                return image.encodeToData(EncodedImageFormat.PNG, 100, 0).getBytes();
            }
        }
    }

    private static byte[] gids(int... gids) {
        ByteBuffer b = ByteBuffer.allocate(gids.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int gid : gids) b.putInt(gid);
        return b.array();
    }

    private Path write(String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    /** A renderer that remembers what it was asked and answers everything else with zero. */
    private static Renderer recorder(List<String> calls, List<Object[]> texts) {
        return (Renderer) Proxy.newProxyInstance(Renderer.class.getClassLoader(), new Class<?>[]{Renderer.class}, (proxy, method, args) -> {
            calls.add(method.getName());
            if (method.getName().equals("drawText") && texts != null) texts.add(args);
            Class<?> type = method.getReturnType();
            if (type == boolean.class) return false;
            if (type == float.class) return 1f;
            if (type == int.class) return 0;
            if (type == double.class) return 0.0;
            if (type == long.class) return 0L;
            return null;
        });
    }

    // ---- zstd

    @Test
    void aLayerCompressedWithZstdLoads() throws IOException {
        Files.write(dir.resolve("tiles.png"), png());
        byte[] packed = TileCompression.zstdCompress(gids(1, 2, 3, 4));
        write("zstd.tmj", """
                {"type":"map","orientation":"orthogonal","width":2,"height":2,"tilewidth":4,"tileheight":4,"infinite":false,
                 "tilesets":[{"firstgid":1,"name":"t","image":"tiles.png","imagewidth":8,"imageheight":8,"tilewidth":4,"tileheight":4,"tilecount":4,"columns":2}],
                 "layers":[{"type":"tilelayer","name":"ground","width":2,"height":2,"x":0,"y":0,"encoding":"base64","compression":"zstd","data":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(packed)));
        TileMap map = TileMap.load(dir.resolve("zstd.tmj"));
        TileLayer ground = map.getTileLayer("ground");
        assertEquals(0, ground.getTile(0, 0).getId());
        assertEquals(1, ground.getTile(1, 0).getId());
        assertEquals(2, ground.getTile(0, 1).getId());
        assertEquals(3, ground.getTile(1, 1).getId());
    }

    @Test
    void zstdThatIsDamagedSaysSo() throws IOException {
        Files.write(dir.resolve("tiles.png"), png());
        byte[] packed = TileCompression.zstdCompress(gids(1, 2, 3, 4));
        packed = java.util.Arrays.copyOf(packed, packed.length / 2); // cut short
        write("bad.tmj", """
                {"type":"map","orientation":"orthogonal","width":2,"height":2,"tilewidth":4,"tileheight":4,
                 "tilesets":[{"firstgid":1,"name":"t","image":"tiles.png","tilewidth":4,"tileheight":4}],
                 "layers":[{"type":"tilelayer","name":"ground","width":2,"height":2,"encoding":"base64","compression":"zstd","data":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(packed)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TileMap.load(dir.resolve("bad.tmj")));
        assertTrue(e.getMessage().contains("bad.tmj") && e.getMessage().toLowerCase().contains("zstd"), e.getMessage());
    }

    // ---- embedded images

    @Test
    void aTileSetWithItsImageInsideTheFileLoads() throws IOException {
        String data = Base64.getEncoder().encodeToString(png());
        write("embedded.tmx", """
                <?xml version="1.0" encoding="UTF-8"?>
                <map version="1.10" orientation="orthogonal" width="2" height="2" tilewidth="4" tileheight="4" infinite="0">
                 <tileset firstgid="1" name="t" tilewidth="4" tileheight="4" tilecount="4" columns="2">
                  <image format="png" width="8" height="8"><data encoding="base64">%s</data></image>
                 </tileset>
                 <layer id="1" name="ground" width="2" height="2"><data encoding="csv">1,2,3,4</data></layer>
                </map>
                """.formatted(data));
        TileMap map = TileMap.load(dir.resolve("embedded.tmx"));
        assertEquals(4, map.getTileSet("t").getTiles().size());
        assertEquals(3, map.getTileLayer("ground").getTile(1, 1).getId());
        assertEquals(4, map.getTileSet("t").getTexture() == null ? 0 : map.getTileLayer("ground").getTile(0, 0).getWidth());
    }

    @Test
    void anEmbeddedImageCanBeCompressedToo() throws IOException {
        byte[] png = png();
        for (String compression : new String[]{"zstd"}) {
            byte[] packed = TileCompression.zstdCompress(png);
            write("packed.tmx", """
                    <map version="1.10" orientation="orthogonal" width="1" height="1" tilewidth="4" tileheight="4">
                     <tileset firstgid="1" name="t" tilewidth="4" tileheight="4" tilecount="4" columns="2">
                      <image format="png" width="8" height="8"><data encoding="base64" compression="%s">%s</data></image>
                     </tileset>
                     <layer id="1" name="a" width="1" height="1"><data encoding="csv">2</data></layer>
                    </map>
                    """.formatted(compression, Base64.getEncoder().encodeToString(packed)));
            assertEquals(1, TileMap.load(dir.resolve("packed.tmx")).getTileLayer("a").getTile(0, 0).getId());
        }
    }

    // ---- blend modes

    @Test
    void aLayerWithAModeIsDrawnAsOneBlendedUnit() throws IOException {
        Files.write(dir.resolve("tiles.png"), png());
        write("blend.tmj", """
                {"type":"map","orientation":"orthogonal","width":1,"height":1,"tilewidth":4,"tileheight":4,
                 "tilesets":[{"firstgid":1,"name":"t","image":"tiles.png","tilewidth":4,"tileheight":4}],
                 "layers":[{"type":"tilelayer","name":"base","width":1,"height":1,"data":[1]},
                           {"type":"tilelayer","name":"glow","width":1,"height":1,"data":[2],"mode":"color-dodge"},
                           {"type":"tilelayer","name":"shade","width":1,"height":1,"data":[3],"mode":"Multiply"}]}
                """);
        TileMap map = TileMap.load(dir.resolve("blend.tmj"));
        assertEquals(BlendMode.NORMAL, map.getLayer("base").getBlendMode());
        assertEquals(BlendMode.COLOR_DODGE, map.getLayer("glow").getBlendMode());
        assertEquals(BlendMode.MULTIPLY, map.getLayer("shade").getBlendMode());

        List<String> calls = new ArrayList<>();
        map.draw(recorder(calls, null));
        assertEquals(2, calls.stream().filter("pushBlendLayer"::equals).count(), "only the two layers with a mode");
        assertEquals(2, calls.stream().filter("popOpacityLayer"::equals).count());
        assertTrue(calls.indexOf("pushBlendLayer") < calls.lastIndexOf("popOpacityLayer"));
    }

    @Test
    void blendModeNamesAreReadLeniently() {
        assertEquals(BlendMode.HARD_LIGHT, BlendMode.fromName("hard-light"));
        assertEquals(BlendMode.ADD, BlendMode.fromName("add"));
        assertEquals(BlendMode.NORMAL, BlendMode.fromName("something-new"));
        assertEquals(BlendMode.NORMAL, BlendMode.fromName(null));
    }

    private int pixel(TileMap map, int size, int x, int y) {
        try (me.piitex.engine.gl.renderer.OffscreenRenderer r = new me.piitex.engine.gl.renderer.OffscreenRenderer(size, size, 1f)) {
            r.begin();
            map.draw(r);
            r.end();
            try (Image img = r.makeImage(); Bitmap bmp = new Bitmap()) {
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                return bmp.getColor(x, y);
            }
        }
    }

    private TileMap twoLayers(String mode) throws IOException {
        Files.write(dir.resolve("tiles.png"), png());
        write("px.tmj", """
                {"type":"map","orientation":"orthogonal","width":1,"height":1,"tilewidth":4,"tileheight":4,
                 "tilesets":[{"firstgid":1,"name":"t","image":"tiles.png","tilewidth":4,"tileheight":4}],
                 "layers":[{"type":"tilelayer","name":"base","width":1,"height":1,"data":[1]},
                           {"type":"tilelayer","name":"top","width":1,"height":1,"data":[2],"mode":"%s"}]}
                """.formatted(mode));
        TileMap map = TileMap.load(dir.resolve("px.tmj"));
        map.setSmooth(false);
        return map;
    }

    @Test
    void aNormalLayerCoversWhatIsBelow() throws IOException {
        assertEquals(0xFF00FF00, pixel(twoLayers("normal"), 4, 2, 2), "green over red is green");
    }

    @Test
    void aMultiplyLayerDarkensWhatIsBelow() throws IOException {
        assertEquals(0xFF000000, pixel(twoLayers("multiply"), 4, 2, 2), "green times red is black");
    }

    @Test
    void anAddLayerBrightensWhatIsBelow() throws IOException {
        assertEquals(0xFFFFFF00, pixel(twoLayers("add"), 4, 2, 2), "red plus green is yellow");
    }

    @Test
    void aScreenLayerLightensWhatIsBelow() throws IOException {
        assertEquals(0xFFFFFF00, pixel(twoLayers("screen"), 4, 2, 2), "screen of pure red and green is yellow too");
    }

    @Test
    void aTextObjectLeavesInkInsideItsBoxAndNothingOutside() throws IOException {
        write("ink.tmj", """
                {"type":"map","orientation":"orthogonal","width":8,"height":4,"tilewidth":8,"tileheight":8,
                 "layers":[{"type":"objectgroup","name":"o","objects":[
                   {"id":1,"x":0,"y":0,"width":32,"height":16,"text":{"text":"HHHH","pixelsize":14,"color":"#ff0000"}}]}]}
                """);
        TileMap map = TileMap.load(dir.resolve("ink.tmj"));
        int red = 0, outside = 0;
        try (me.piitex.engine.gl.renderer.OffscreenRenderer r = new me.piitex.engine.gl.renderer.OffscreenRenderer(64, 32, 1f)) {
            r.begin();
            map.draw(r);
            r.end();
            try (Image img = r.makeImage(); Bitmap bmp = new Bitmap()) {
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 64; x++) {
                        int c = bmp.getColor(x, y);
                        boolean ink = (c >>> 24) > 0 && ((c >> 16) & 0xFF) > 100;
                        if (ink && x < 32 && y < 16) red++;
                        else if (ink) outside++;
                    }
                }
            }
        }
        assertTrue(red > 20, "the letters are drawn in red inside the box: " + red);
        assertEquals(0, outside, "and clipped to it");
    }

    // ---- text objects

    private TileMap textMap() throws IOException {
        write("text.tmx", """
                <map version="1.10" orientation="orthogonal" width="4" height="4" tilewidth="8" tileheight="8">
                 <objectgroup id="1" name="signs">
                  <object id="1" name="sign" x="8" y="16" width="64" height="24" rotation="10">
                   <text fontfamily="Serif" pixelsize="12" wrap="1" color="#ff8800" bold="1" underline="1" halign="center" valign="bottom">Hello there</text>
                  </object>
                  <object id="2" name="hidden" x="0" y="0" width="30" height="10" visible="0"><text>nope</text></object>
                  <object id="3" name="marker" x="0" y="0" width="5" height="5"/>
                 </objectgroup>
                </map>
                """);
        return TileMap.load(dir.resolve("text.tmx"));
    }

    @Test
    void aTextObjectKeepsItsFormat() throws IOException {
        MapObject sign = textMap().findObject("sign");
        ObjectText format = sign.getTextFormat();
        assertEquals("Hello there", sign.getText());
        assertEquals("Serif", format.fontFamily());
        assertEquals(12f, format.pixelSize());
        assertTrue(format.wrap() && format.bold() && format.underline());
        assertFalse(format.italic() || format.strikeout());
        assertEquals(TextAlign.CENTER, format.horizontal());
        assertEquals(1f, format.vertical());
        assertEquals(0xFFFF8800, format.color().toARGB());
        assertNull(textMap().findObject("marker").getTextFormat());
    }

    @Test
    void aVisibleTextObjectIsDrawnInItsBoxAndOthersAreNot() throws IOException {
        TileMap map = textMap();
        List<String> calls = new ArrayList<>();
        List<Object[]> texts = new ArrayList<>();
        map.draw(recorder(calls, texts));
        assertTrue(texts.stream().anyMatch(a -> "Hello there".equals(a[0]) || "Hello".equals(a[0]) || "there".equals(a[0])),
                "the sign's words are drawn");
        assertTrue(texts.stream().noneMatch(a -> "nope".equals(a[0])), "a hidden text object isn't");
        assertTrue(calls.contains("pushTransform") && calls.contains("pushClip"), "turned and clipped to its box");
        assertTrue(calls.contains("drawLine"), "the underline");
    }

    // ---- project defaults

    private TiledProject project() throws IOException {
        write("game.tiled-project", """
                {"propertyTypes":[
                  {"id":1,"name":"Stats","type":"class","members":[
                     {"name":"atk","type":"int","value":3},{"name":"def","type":"int","value":1}]},
                  {"id":2,"name":"Enemy","type":"class","members":[
                     {"name":"hp","type":"int","value":10},
                     {"name":"speed","type":"float","value":1.5},
                     {"name":"label","type":"string","value":"orc"},
                     {"name":"flying","type":"bool","value":false},
                     {"name":"stats","type":"class","propertyType":"Stats","value":{"atk":5}}]},
                  {"id":3,"name":"Mood","type":"enum","values":["calm","angry"]}
                ]}
                """);
        return TiledProject.load(dir.resolve("game.tiled-project"));
    }

    private void enemyMap() throws IOException {
        write("enemies.tmj", """
                {"type":"map","orientation":"orthogonal","width":2,"height":2,"tilewidth":8,"tileheight":8,
                 "layers":[{"type":"objectgroup","name":"o","objects":[
                    {"id":1,"name":"orc","x":0,"y":0,"width":8,"height":8,"class":"Enemy",
                     "properties":[{"name":"hp","type":"int","value":20}]},
                    {"id":2,"name":"holder","x":0,"y":0,"width":8,"height":8,
                     "properties":[{"name":"boss","type":"class","propertytype":"Enemy","value":{"speed":3,"stats":{"def":9}}}]}
                 ]}]}
                """);
    }

    @Test
    void anObjectOfAClassGetsTheClassDefaultsUnderItsOwnValues() throws IOException {
        enemyMap();
        TileMap map = TileMap.load(dir.resolve("enemies.tmj"), project());
        Properties orc = map.findObject("orc").getProperties();
        assertEquals(20, orc.getInt("hp", -1), "what the map sets wins");
        assertEquals(1.5f, orc.getFloat("speed", -1f), "the rest is the class default");
        assertEquals("orc", orc.getString("label"));
        assertFalse(orc.getBoolean("flying", true));
        assertEquals(5, orc.getMembers("stats").getInt("atk", -1), "the Enemy default for the member beats the Stats one");
        assertEquals(1, orc.getMembers("stats").getInt("def", -1), "and what Enemy leaves alone is the Stats default");
    }

    @Test
    void aClassTypedPropertyIsFilledInAndMerged() throws IOException {
        enemyMap();
        TileMap map = TileMap.load(dir.resolve("enemies.tmj"), project());
        Properties boss = map.findObject("holder").getProperties().getMembers("boss");
        assertEquals(10, boss.getInt("hp", -1));
        assertEquals(3f, boss.getFloat("speed", -1f));
        assertEquals(5, boss.getMembers("stats").getInt("atk", -1));
        assertEquals(9, boss.getMembers("stats").getInt("def", -1));
    }

    @Test
    void withoutTheProjectOnlyWhatTheMapSetsIsThere() throws IOException {
        enemyMap();
        TileMap map = TileMap.load(dir.resolve("enemies.tmj"));
        Properties orc = map.findObject("orc").getProperties();
        assertEquals(20, orc.getInt("hp", -1));
        assertEquals(-1f, orc.getFloat("speed", -1f));
        assertFalse(orc.has("stats"));
    }

    @Test
    void theProjectListsItsClasses() throws IOException {
        TiledProject project = project();
        assertEquals(java.util.Set.of("Stats", "Enemy"), project.getClassNames(), "enums aren't classes");
        assertTrue(project.hasClass("Enemy"));
        assertThrows(IllegalArgumentException.class, () -> {
            write("bad.tiled-project", "[1,2]");
            TiledProject.load(dir.resolve("bad.tiled-project"));
        });
    }

    @Test
    void classDefaultsWorkInXmlMapsToo() throws IOException {
        write("xml.tmx", """
                <map version="1.10" orientation="orthogonal" width="1" height="1" tilewidth="8" tileheight="8">
                 <objectgroup id="1" name="o">
                  <object id="1" name="orc" class="Enemy" x="0" y="0" width="8" height="8">
                   <properties><property name="hp" type="int" value="40"/></properties>
                  </object>
                  <object id="2" name="holder" x="0" y="0" width="8" height="8">
                   <properties><property name="boss" type="class" propertytype="Enemy"><properties><property name="speed" type="float" value="2"/></properties></property></properties>
                  </object>
                 </objectgroup>
                </map>
                """);
        TileMap map = TileMap.load(dir.resolve("xml.tmx"), project());
        Properties orc = map.findObject("orc").getProperties();
        assertEquals(40, orc.getInt("hp", -1));
        assertEquals("orc", orc.getString("label"));
        Properties boss = map.findObject("holder").getProperties().getMembers("boss");
        assertEquals(2f, boss.getFloat("speed", -1f));
        assertEquals(10, boss.getInt("hp", -1));
    }
}
