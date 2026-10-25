package me.piitex.engine.tilemap;

import me.piitex.engine.gl.renderer.OffscreenRenderer;
import me.piitex.engine.math.Polygon;
import me.piitex.engine.math.Rect;
import me.piitex.engine.math.Shape;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Isometric, staggered and hexagonal maps: where cells are, which cell a point is in, and what is drawn. */
class TileMapOrientationTest {
    @TempDir
    Path dir;

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

    private TileMap load(String name, String json) throws IOException {
        Files.write(dir.resolve("tiles.png"), png());
        Files.writeString(dir.resolve(name), json);
        TileMap map = TileMap.load(dir.resolve(name));
        map.setSmooth(false);
        return map;
    }

    private static List<TileMap> every() {
        return List.of(
                TileMap.isometric(7, 5, 64, 32),
                TileMap.staggered(7, 5, 64, 32, TileMap.StaggerAxis.Y, true),
                TileMap.staggered(7, 5, 64, 32, TileMap.StaggerAxis.Y, false),
                TileMap.staggered(7, 5, 64, 32, TileMap.StaggerAxis.X, true),
                TileMap.staggered(7, 5, 64, 32, TileMap.StaggerAxis.X, false),
                TileMap.hexagonal(7, 5, 56, 64, 32, TileMap.StaggerAxis.Y, true),
                TileMap.hexagonal(7, 5, 56, 64, 32, TileMap.StaggerAxis.Y, false),
                TileMap.hexagonal(7, 5, 64, 56, 28, TileMap.StaggerAxis.X, true),
                TileMap.hexagonal(7, 5, 64, 56, 28, TileMap.StaggerAxis.X, false),
                new TileMap(7, 5, 32, 32));
    }

    // ---- where cells are -----------------------------------------------------------------------------------------

    @Test
    void anIsometricMapIsADiamondWithCellZeroAtTheTop() {
        TileMap map = TileMap.isometric(4, 4, 64, 32);
        Rect bounds = map.getBounds();
        assertEquals(256, bounds.width(), 1e-4);
        assertEquals(128, bounds.height(), 1e-4);
        Rect first = map.getCellBounds(0, 0);
        assertEquals(128 - 32, first.x(), 1e-4, "its box is centred on the middle of the map");
        assertEquals(0, first.y(), 1e-4);
        float[] center = map.getCellCenter(1, 0);
        assertEquals(128 + 32, center[0], 1e-4, "x runs down and to the right");
        assertEquals(32, center[1], 1e-4);
        float[] other = map.getCellCenter(0, 1);
        assertEquals(128 - 32, other[0], 1e-4, "y runs down and to the left");
        assertEquals(32, other[1], 1e-4);
    }

    @Test
    void everyCellsCentreIsInThatCellInEveryOrientation() {
        for (TileMap map : every()) {
            for (int y = -3; y < 9; y++) {
                for (int x = -3; x < 10; x++) {
                    float[] c = map.getCellCenter(x, y);
                    TileMap.Cell cell = map.toCell(c[0], c[1]);
                    assertEquals(new TileMap.Cell(x, y), cell, map.getOrientation() + " " + map.getStaggerAxis() + " odd=" + map.isStaggerOdd());
                }
            }
        }
    }

    @Test
    void aPointIsInTheShapeOfTheCellItIsGiven() {
        Random random = new Random(7);
        for (TileMap map : every()) {
            Rect bounds = map.getBounds();
            for (int i = 0; i < 400; i++) {
                float x = bounds.x() + random.nextFloat() * bounds.width(), y = bounds.y() + random.nextFloat() * bounds.height();
                TileMap.Cell cell = map.toCell(x, y);
                Shape shape = map.getCellShape(cell.x(), cell.y());
                assertTrue(shape.contains(x, y) || onEdge(shape, x, y), map.getOrientation() + ": (" + x + ", " + y + ") in " + cell);
            }
        }
    }

    /** A point a hair outside a shape, which the cell it is on the border of still owns. */
    private static boolean onEdge(Shape shape, float x, float y) {
        return shape.contains(x + 0.01f, y) || shape.contains(x - 0.01f, y) || shape.contains(x, y + 0.01f) || shape.contains(x, y - 0.01f);
    }

    @Test
    void cellsDoNotOverlapAndFillTheMap() {
        // the centre of a cell is in no other cell's shape, on every orientation
        for (TileMap map : every()) {
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 7; x++) {
                    float[] c = map.getCellCenter(x, y);
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            assertFalse(map.getCellShape(x + dx, y + dy).contains(c[0], c[1]),
                                    map.getOrientation() + " cell " + x + "," + y + " is also in " + (x + dx) + "," + (y + dy));
                        }
                    }
                }
            }
        }
    }

    @Test
    void theShapeOfACellIsADiamondOrAHexagon() {
        assertInstanceOf(Rect.class, new TileMap(3, 3, 16, 16).getCellShape(1, 1));
        Polygon diamond = (Polygon) TileMap.isometric(3, 3, 64, 32).getCellShape(1, 1);
        assertEquals(8, diamond.getPoints().length, "four corners");
        Polygon hex = (Polygon) TileMap.hexagonal(3, 3, 56, 64, 32, TileMap.StaggerAxis.Y, true).getCellShape(1, 1);
        assertEquals(12, hex.getPoints().length, "six corners");
    }

    @Test
    void staggeredAndHexagonalMapsShiftEveryOtherRowOrColumn() {
        TileMap odd = TileMap.staggered(4, 4, 64, 32, TileMap.StaggerAxis.Y, true);
        assertEquals(0, odd.getCellBounds(0, 0).x(), 1e-4);
        assertEquals(32, odd.getCellBounds(0, 1).x(), 1e-4, "odd rows are half a tile right");
        assertEquals(16, odd.getCellBounds(0, 1).y(), 1e-4, "and rows are half a tile apart");
        assertEquals(0, odd.getCellBounds(0, 2).x(), 1e-4);

        TileMap even = TileMap.staggered(4, 4, 64, 32, TileMap.StaggerAxis.Y, false);
        assertEquals(32, even.getCellBounds(0, 0).x(), 1e-4, "or the even ones");
        assertEquals(0, even.getCellBounds(0, 1).x(), 1e-4);

        TileMap columns = TileMap.staggered(4, 4, 64, 32, TileMap.StaggerAxis.X, true);
        assertEquals(32, columns.getCellBounds(1, 0).x(), 1e-4, "columns are half a tile apart");
        assertEquals(16, columns.getCellBounds(1, 0).y(), 1e-4, "and the shifted ones half a tile down");
        assertEquals(0, columns.getCellBounds(2, 0).y(), 1e-4);
    }

    @Test
    void aHexagonalMapPacksItsRowsByTheSideLength() {
        // pointed tops, 64 wide, 64 high with a side of 32: rows are (64 - 32) / 2 + 32 = 48 apart
        TileMap map = TileMap.hexagonal(4, 4, 64, 64, 32, TileMap.StaggerAxis.Y, true);
        assertEquals(0, map.getCellBounds(0, 0).y(), 1e-4);
        assertEquals(48, map.getCellBounds(0, 1).y(), 1e-4);
        assertEquals(32, map.getCellBounds(0, 1).x(), 1e-4, "odd rows half a width right");
        assertEquals(4 * 64 + 32, map.getBounds().width(), 1e-4);
        assertEquals(4 * 48 + 16, map.getBounds().height(), 1e-4);

        TileMap flat = TileMap.hexagonal(4, 4, 64, 64, 32, TileMap.StaggerAxis.X, true);
        assertEquals(48, flat.getCellBounds(1, 0).x(), 1e-4, "columns (64 - 32) / 2 + 32 apart");
        assertEquals(32, flat.getCellBounds(1, 0).y(), 1e-4, "odd columns half a height down");
        assertEquals(4 * 48 + 16, flat.getBounds().width(), 1e-4);
        assertEquals(4 * 64 + 32, flat.getBounds().height(), 1e-4);
    }

    @Test
    void staggeredBoundsFollowTheShiftedCells() {
        Rect rows = TileMap.staggered(5, 4, 64, 32, TileMap.StaggerAxis.Y, true).getBounds();
        assertEquals(5 * 64 + 32, rows.width(), 1e-4);
        assertEquals(4 * 16 + 16, rows.height(), 1e-4);
        Rect columns = TileMap.staggered(5, 4, 64, 32, TileMap.StaggerAxis.X, true).getBounds();
        assertEquals(5 * 32 + 32, columns.width(), 1e-4);
        assertEquals(4 * 32 + 16, columns.height(), 1e-4);
    }

    @Test
    void aHexagonNeedsASensibleSide() {
        assertThrows(IllegalArgumentException.class, () -> TileMap.hexagonal(2, 2, 64, 64, 0, TileMap.StaggerAxis.Y, true));
        assertThrows(IllegalArgumentException.class, () -> TileMap.hexagonal(2, 2, 64, 64, 64, TileMap.StaggerAxis.Y, true));
        assertThrows(IllegalArgumentException.class, () -> TileMap.hexagonal(2, 2, 64, 40, 40, TileMap.StaggerAxis.Y, true), "side against the height for rows");
        assertDoesNotThrow(() -> TileMap.hexagonal(2, 2, 40, 64, 40, TileMap.StaggerAxis.Y, true));
    }

    @Test
    void theColumnOfAWorldXMeansNothingOffTheOrthogonalGrid() {
        assertEquals(2, new TileMap(3, 3, 16, 16).toTileX(33f));
        assertThrows(UnsupportedOperationException.class, () -> TileMap.isometric(3, 3, 64, 32).toTileX(10f));
        assertThrows(UnsupportedOperationException.class, () -> TileMap.hexagonal(3, 3, 56, 64, 32, TileMap.StaggerAxis.Y, true).toTileY(10f));
    }

    // ---- loading -------------------------------------------------------------------------------------------------

    private static String map(String attributes, int w, int h, int tw, int th, String data) {
        return """
                {"type":"map",%s,"width":%d,"height":%d,"tilewidth":%d,"tileheight":%d,
                 "tilesets":[{"firstgid":1,"name":"t","image":"tiles.png","imagewidth":8,"imageheight":8,"tilewidth":4,"tileheight":4,"tilecount":4,"columns":2}],
                 "layers":[{"type":"tilelayer","name":"ground","width":%d,"height":%d,"data":[%s]}]}
                """.formatted(attributes, w, h, tw, th, w, h, data);
    }

    @Test
    void theOrientationAndStaggerSettingsAreRead() throws IOException {
        TileMap iso = load("iso.tmj", map("\"orientation\":\"isometric\"", 3, 3, 8, 4, "1,1,1,1,1,1,1,1,1"));
        assertEquals(TileMap.Orientation.ISOMETRIC, iso.getOrientation());

        TileMap staggered = load("stag.tmj", map("\"orientation\":\"staggered\",\"staggeraxis\":\"x\",\"staggerindex\":\"even\"", 3, 3, 8, 4, "1,1,1,1,1,1,1,1,1"));
        assertEquals(TileMap.Orientation.STAGGERED, staggered.getOrientation());
        assertEquals(TileMap.StaggerAxis.X, staggered.getStaggerAxis());
        assertFalse(staggered.isStaggerOdd());

        TileMap hex = load("hex.tmj", map("\"orientation\":\"hexagonal\",\"staggeraxis\":\"y\",\"staggerindex\":\"odd\",\"hexsidelength\":4", 3, 3, 8, 8, "1,1,1,1,1,1,1,1,1"));
        assertEquals(TileMap.Orientation.HEXAGONAL, hex.getOrientation());
        assertEquals(4, hex.getHexSideLength());
        assertEquals(TileMap.StaggerAxis.Y, hex.getStaggerAxis());
        assertTrue(hex.isStaggerOdd());

        assertEquals(TileMap.Orientation.ORTHOGONAL, load("ortho.tmj", map("\"orientation\":\"orthogonal\"", 3, 3, 8, 4, "1,1,1,1,1,1,1,1,1")).getOrientation());
    }

    @Test
    void aMapInTmxIsReadToo() throws IOException {
        Files.write(dir.resolve("tiles.png"), png());
        Files.writeString(dir.resolve("hex.tmx"), """
                <map version="1.10" orientation="hexagonal" width="2" height="2" tilewidth="8" tileheight="8" hexsidelength="4" staggeraxis="x" staggerindex="even">
                 <tileset firstgid="1" name="t" tilewidth="4" tileheight="4" tilecount="4" columns="2"><image source="tiles.png" width="8" height="8"/></tileset>
                 <layer id="1" name="ground" width="2" height="2"><data encoding="csv">1,2,3,4</data></layer>
                </map>
                """);
        TileMap map = TileMap.load(dir.resolve("hex.tmx"));
        assertEquals(TileMap.Orientation.HEXAGONAL, map.getOrientation());
        assertEquals(TileMap.StaggerAxis.X, map.getStaggerAxis());
        assertFalse(map.isStaggerOdd());
        assertEquals(4, map.getHexSideLength());
    }

    @Test
    void aHexagonalMapWithoutASideLengthIsRefusedByName() throws IOException {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("bad.tmj", map("\"orientation\":\"hexagonal\"", 2, 2, 8, 8, "1,1,1,1")));
        assertTrue(e.getMessage().contains("side length"), e.getMessage());
    }

    @Test
    void anInfiniteMapMustBeOrthogonal() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load("inf.tmj", """
                {"type":"map","orientation":"isometric","infinite":true,"width":0,"height":0,"tilewidth":8,"tileheight":4,"layers":[]}
                """));
        assertTrue(e.getMessage().contains("infinite") && e.getMessage().contains("isometric"), e.getMessage());
    }

    @Test
    void anUnknownOrientationIsNamed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load("odd.tmj", """
                {"type":"map","orientation":"triangular","width":1,"height":1,"tilewidth":8,"tileheight":4,"layers":[]}
                """));
        assertTrue(e.getMessage().contains("triangular"), e.getMessage());
    }

    // ---- tiles under a point and collision -----------------------------------------------------------------------

    @Test
    void thePointerFindsTheTileOfAnIsometricCell() throws IOException {
        // ids 1, 2, 3: green is tile 1, blue tile 2 in a 4 by 4 picture; 3 by 3 diamonds 8 by 4
        TileMap map = load("iso.tmj", map("\"orientation\":\"isometric\"", 3, 3, 8, 4, "1,2,2, 3,3,3, 4,4,4"));
        TileLayer ground = map.getTileLayer("ground");
        float[] c = map.getCellCenter(1, 0);
        assertEquals(1, ground.getTileAt(c[0], c[1]).getId());
        c = map.getCellCenter(0, 0);
        assertEquals(0, ground.getTileAt(c[0], c[1]).getId());
        c = map.getCellCenter(0, 1);
        assertEquals(2, ground.getTileAt(c[0], c[1]).getId());
        assertNull(ground.getTileAt(-50f, -50f));
        assertNull(ground.getTileAt(1000f, 1000f));
    }

    @Test
    void solidCellsOfANonOrthogonalMapAreDiamondsAndHexagons() throws IOException {
        TileMap map = load("iso.tmj", map("\"orientation\":\"isometric\"", 3, 3, 8, 4, "1,1,2, 1,2,2, 3,3,3"));
        TileLayer ground = map.getTileLayer("ground");
        Predicate<Tile> green = tile -> tile.getId() == 1;
        List<Shape> shapes = ground.getSolidShapes(green);
        assertEquals(3, shapes.size());
        for (Shape shape : shapes) assertInstanceOf(Polygon.class, shape);
        float[] c = map.getCellCenter(2, 0); // tile 1 is green: cells (2, 0), (1, 1) and (2, 1)
        assertTrue(shapes.stream().anyMatch(s -> s.contains(c[0], c[1])));
        float[] other = map.getCellCenter(1, 0); // tile 0, red
        assertTrue(shapes.stream().noneMatch(s -> s.contains(other[0], other[1])), "a red cell is not solid");
        assertThrows(UnsupportedOperationException.class, () -> ground.getSolidRects(green));

        TileMap flat = load("flat.tmj", map("\"orientation\":\"orthogonal\"", 3, 3, 4, 4, "1,1,2, 1,2,2, 3,3,3"));
        List<Shape> rects = flat.getTileLayer("ground").getSolidShapes(green);
        assertFalse(rects.isEmpty());
        for (Shape shape : rects) assertInstanceOf(Rect.class, shape);
    }

    @Test
    void aLayersOffsetMovesTheCellsItFinds() throws IOException {
        TileMap map = load("iso.tmj", map("\"orientation\":\"isometric\"", 3, 3, 8, 4, "1,2,2, 3,3,3, 4,4,4"));
        TileLayer ground = map.getTileLayer("ground");
        ground.setOffset(100f, 0f);
        float[] c = map.getCellCenter(1, 0);
        assertEquals(1, ground.getTileAt(c[0] + 100f, c[1]).getId());
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    private static int pixel(Bitmap bmp, int x, int y) {
        return bmp.getColor(x, y);
    }

    private static Bitmap draw(TileMap map, int w, int h, Rect view) {
        try (OffscreenRenderer r = new OffscreenRenderer(w, h, 1f)) {
            r.begin();
            map.draw(r, view);
            r.end();
            try (Image img = r.makeImage()) {
                Bitmap bmp = new Bitmap();
                bmp.allocPixels(img.getImageInfo());
                img.readPixels(bmp);
                return bmp;
            }
        }
    }

    @Test
    void anIsometricTileIsCentredAcrossItsCellAndStandsOnItsBottom() throws IOException {
        // diamonds 8 by 4; the pictures are 4 by 4, narrower than the cell, so centred in it
        TileMap map = load("iso.tmj", map("\"orientation\":\"isometric\"", 3, 3, 8, 4, "1,2,2, 3,3,3, 4,4,4"));
        Rect bounds = map.getBounds();
        assertEquals(24, bounds.width(), 1e-4);
        assertEquals(12, bounds.height(), 1e-4);
        try (Bitmap bmp = draw(map, 32, 16, null)) {
            // cell (0, 0): box at x 8, y 0; the picture at x 10 to 14, y 0 to 4, tile 0 (red)
            assertEquals(0xFFFF0000, pixel(bmp, 12, 2));
            assertNotEquals(0xFFFF0000, pixel(bmp, 9, 2), "left of the centred picture");
            // cell (1, 0): box at x 12, y 2; the picture at x 14 to 18, y 2 to 6, tile 1 (green)
            assertEquals(0xFF00FF00, pixel(bmp, 16, 4));
            // cell (0, 1): box at x 4, y 2; the picture at x 6 to 10, y 2 to 6, tile 2 (blue)
            assertEquals(0xFF0000FF, pixel(bmp, 8, 4));
        }
    }

    private static String bigMap(String attributes, int w, int h, int tw, int th) {
        StringBuilder data = new StringBuilder();
        Random random = new Random(3);
        for (int i = 0; i < w * h; i++) data.append(i == 0 ? "" : ",").append(1 + random.nextInt(4));
        return map(attributes, w, h, tw, th, data.toString());
    }

    @Test
    void whatIsInViewIsDrawnTheSameWhetherOrNotTheRestIsCulled() throws IOException {
        String[][] kinds = {
                {"\"orientation\":\"isometric\"", "8", "4"},
                {"\"orientation\":\"staggered\",\"staggeraxis\":\"y\",\"staggerindex\":\"odd\"", "8", "4"},
                {"\"orientation\":\"staggered\",\"staggeraxis\":\"x\",\"staggerindex\":\"even\"", "8", "4"},
                {"\"orientation\":\"hexagonal\",\"staggeraxis\":\"y\",\"staggerindex\":\"odd\",\"hexsidelength\":4", "8", "8"},
                {"\"orientation\":\"hexagonal\",\"staggeraxis\":\"x\",\"staggerindex\":\"odd\",\"hexsidelength\":4", "8", "8"},
        };
        for (String[] kind : kinds) {
            TileMap map = load("big.tmj", bigMap(kind[0], 24, 24, Integer.parseInt(kind[1]), Integer.parseInt(kind[2])));
            Rect bounds = map.getBounds();
            int w = (int) Math.ceil(bounds.width()) + 8, h = (int) Math.ceil(bounds.height()) + 8;
            Rect view = new Rect(bounds.width() * 0.3f, bounds.height() * 0.3f, bounds.width() * 0.3f, bounds.height() * 0.3f);
            try (Bitmap everything = draw(map, w, h, null); Bitmap culled = draw(map, w, h, view)) {
                int checked = 0;
                for (int y = (int) view.y(); y < view.bottom(); y++) {
                    for (int x = (int) view.x(); x < view.right(); x++) {
                        assertEquals(everything.getColor(x, y), culled.getColor(x, y), kind[0] + " at " + x + ", " + y);
                        checked++;
                    }
                }
                assertTrue(checked > 100);
                // and the culling really drops what is far from the view
                assertEquals(0, culled.getColor(1, 1) >>> 24, kind[0] + ": the far corner is not drawn");
            }
        }
    }

    @Test
    void aTileBiggerThanItsCellOnAStaggeredMapReachesUpFromTheCellsBottomLeft() throws IOException {
        TileMap map = load("stag.tmj", map("\"orientation\":\"staggered\"", 3, 3, 4, 2, "1,1,1, 1,1,1, 1,1,1"));
        // cells are 4 by 2 boxes with 4 by 4 pictures: the picture of cell (0, 0) is at y -2 to 2
        float[] where = new float[4];
        TileLayer.placeTile(map, map.getTileLayer("ground").getTile(0, 0), 0, 0, 0, 0f, 0f, where);
        assertEquals(0f, where[0], 1e-4);
        assertEquals(-2f, where[1], 1e-4);
        TileLayer.placeTile(map, map.getTileLayer("ground").getTile(0, 1), 0, 0, 1, 0f, 0f, where);
        assertEquals(2f, where[0], 1e-4, "odd rows are half a cell right");
        assertEquals(-1f, where[1], 1e-4);
    }

    // ---- objects on an isometric map -----------------------------------------------------------------------------

    private TileMap isoObjects() throws IOException {
        return load("iso-objects.tmj", """
                {"type":"map","orientation":"isometric","width":4,"height":4,"tilewidth":64,"tileheight":32,
                 "tilesets":[{"firstgid":1,"name":"t","image":"tiles.png","imagewidth":8,"imageheight":8,"tilewidth":4,"tileheight":4,"tilecount":4,"columns":2}],
                 "layers":[{"type":"objectgroup","name":"o","objects":[
                   {"id":1,"name":"spot","x":64,"y":32,"point":true},
                   {"id":2,"name":"floor","x":0,"y":0,"width":32,"height":32},
                   {"id":3,"name":"path","x":0,"y":0,"polyline":[{"x":0,"y":0},{"x":32,"y":0},{"x":32,"y":32}]},
                   {"id":4,"name":"crate","x":32,"y":32,"gid":1,"width":4,"height":4}]}]}
                """);
    }

    @Test
    void objectsOnAnIsometricMapAreReadWhereTheyAreDrawn() throws IOException {
        TileMap map = isoObjects();
        // 64 and 32 on a grid of 32 are tile (2, 1): ((2 - 1) * 32 + 4 * 32, (2 + 1) * 16)
        MapObject spot = map.findObject("spot");
        assertEquals(160f, spot.getX(), 1e-3);
        assertEquals(48f, spot.getY(), 1e-3);

        MapObject floor = map.findObject("floor");
        assertEquals(MapObject.Kind.POLYGON, floor.getKind(), "a rectangle on a diamond grid is a parallelogram");
        float[] p = floor.getPoints();
        assertEquals(8, p.length);
        assertArrayEquals(new float[]{128, 0, 160, 16, 128, 32, 96, 16}, p, 1e-3f);
        assertEquals(1, floor.getShapes().size());
        assertTrue(floor.getShapes().getFirst().contains(128f, 16f), "the middle of the first cell is on it");
        assertFalse(floor.getShapes().getFirst().contains(200f, 60f));

        MapObject path = map.findObject("path");
        assertEquals(MapObject.Kind.POLYLINE, path.getKind());
        assertArrayEquals(new float[]{128, 0, 160, 16, 128, 32}, path.getPoints(), 1e-3f);
    }

    @Test
    void aTileObjectOnAnIsometricMapStandsOnItsBottomCentre() throws IOException {
        TileMap map = isoObjects();
        MapObject crate = map.findObject("crate");
        assertEquals(0.5f, crate.getTile().getTileSet().getObjectPivotX(), 1e-6f, "Tiled's default for an isometric map");
        assertEquals(1f, crate.getTile().getTileSet().getObjectPivotY(), 1e-6f);
        assertEquals(128f, crate.getX(), 1e-3, "tile (1, 1) is the middle of the map across");
        assertEquals(32f, crate.getY(), 1e-3);
        Rect box = crate.getBounds();
        assertEquals(126f, box.x(), 1e-3, "centred across its point");
        assertEquals(28f, box.y(), 1e-3, "standing on it");
    }

    @Test
    void anOrthogonalMapsObjectsAreAsBefore() throws IOException {
        Files.write(dir.resolve("tiles.png"), png());
        Files.writeString(dir.resolve("o.tmj"), """
                {"type":"map","orientation":"orthogonal","width":4,"height":4,"tilewidth":32,"tileheight":32,
                 "tilesets":[{"firstgid":1,"name":"t","image":"tiles.png","imagewidth":8,"imageheight":8,"tilewidth":4,"tileheight":4,"tilecount":4,"columns":2}],
                 "layers":[{"type":"objectgroup","name":"o","objects":[
                   {"id":1,"name":"floor","x":10,"y":20,"width":32,"height":16},
                   {"id":2,"name":"crate","x":32,"y":32,"gid":1,"width":4,"height":4}]}]}
                """);
        TileMap map = TileMap.load(dir.resolve("o.tmj"));
        assertEquals(MapObject.Kind.RECTANGLE, map.findObject("floor").getKind());
        assertEquals(10f, map.findObject("floor").getX());
        assertEquals(0f, map.findObject("crate").getTile().getTileSet().getObjectPivotX());
    }
}
