package me.piitex.engine.tilemap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns Tiled's XML formats (maps, tile sets and templates) into the same shape its JSON formats have, so
 * {@link TiledReader} reads one shape. Attribute values stay strings; the reader converts them as it reads.
 */
final class TmxConverter {
    private TmxConverter() {
    }

    static Map<String, Object> convert(String xml) {
        Element root = parse(xml).getDocumentElement();
        return switch (root.getTagName()) {
            case "map" -> map(root);
            case "tileset" -> tileSet(root);
            case "template" -> template(root);
            default -> throw new IllegalArgumentException("not a Tiled file: the XML starts with <" + root.getTagName() + ">");
        };
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Old maps name Tiled's DTD; never fetch it, nor any other outside file.
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (SAXParseException e) {
            throw new IllegalArgumentException("Bad XML at line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " + e.getMessage(), e);
        } catch (SAXException | ParserConfigurationException | IOException e) {
            throw new IllegalArgumentException("Bad XML: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> map(Element map) {
        Map<String, Object> out = attributes(map);
        out.put("type", "map");
        properties(map, out);
        List<Object> sets = new ArrayList<>();
        for (Element set : children(map, "tileset")) sets.add(tileSet(set));
        out.put("tilesets", sets);
        out.put("layers", layers(map));
        return out;
    }

    private static List<Object> layers(Element parent) {
        List<Object> layers = new ArrayList<>();
        for (Element child : children(parent, null)) {
            switch (child.getTagName()) {
                case "layer" -> layers.add(tileLayer(child));
                case "objectgroup" -> layers.add(objectGroup(child));
                case "imagelayer" -> layers.add(imageLayer(child));
                case "group" -> {
                    Map<String, Object> group = attributes(child);
                    group.put("type", "group");
                    properties(child, group);
                    group.put("layers", layers(child));
                    layers.add(group);
                }
                default -> {
                    // properties, tilesets and editor settings are read elsewhere
                }
            }
        }
        return layers;
    }

    private static Map<String, Object> tileLayer(Element layer) {
        Map<String, Object> out = attributes(layer);
        out.put("type", "tilelayer");
        properties(layer, out);
        Element data = child(layer, "data");
        if (data == null) return out;
        String encoding = data.getAttribute("encoding");
        out.put("encoding", encoding.isEmpty() ? "xml" : encoding);
        out.put("compression", data.getAttribute("compression"));
        List<Element> chunks = children(data, "chunk");
        if (chunks.isEmpty()) {
            out.put("data", data(data, encoding));
        } else {
            List<Object> converted = new ArrayList<>();
            for (Element chunk : chunks) {
                Map<String, Object> c = attributes(chunk);
                c.put("data", data(chunk, encoding));
                converted.add(c);
            }
            out.put("chunks", converted);
        }
        return out;
    }

    /**
     * Encoded tile data as its text, or, without an encoding, the gids of the {@code <tile>} elements.
     */
    private static Object data(Element data, String encoding) {
        if (!encoding.isEmpty()) return data.getTextContent();
        List<Object> gids = new ArrayList<>();
        for (Element tile : children(data, "tile")) gids.add(tile.hasAttribute("gid") ? tile.getAttribute("gid") : "0");
        return gids;
    }

    private static Map<String, Object> objectGroup(Element group) {
        Map<String, Object> out = attributes(group);
        out.put("type", "objectgroup");
        properties(group, out);
        List<Object> objects = new ArrayList<>();
        for (Element object : children(group, "object")) objects.add(object(object));
        out.put("objects", objects);
        return out;
    }

    private static Map<String, Object> imageLayer(Element layer) {
        Map<String, Object> out = attributes(layer);
        out.put("type", "imagelayer");
        properties(layer, out);
        image(child(layer, "image"), out);
        return out;
    }

    private static Map<String, Object> object(Element object) {
        Map<String, Object> out = attributes(object);
        properties(object, out);
        if (child(object, "ellipse") != null) out.put("ellipse", Boolean.TRUE);
        if (child(object, "capsule") != null) out.put("capsule", Boolean.TRUE);
        if (child(object, "point") != null) out.put("point", Boolean.TRUE);
        Element polygon = child(object, "polygon");
        if (polygon != null) out.put("polygon", points(polygon.getAttribute("points")));
        Element polyline = child(object, "polyline");
        if (polyline != null) out.put("polyline", points(polyline.getAttribute("points")));
        Element text = child(object, "text");
        if (text != null) {
            Map<String, Object> t = attributes(text);
            t.put("text", text.getTextContent());
            out.put("text", t);
        }
        return out;
    }

    private static List<Object> points(String points) {
        List<Object> out = new ArrayList<>();
        for (String pair : points.trim().split("\\s+")) {
            if (pair.isEmpty()) continue;
            String[] xy = pair.split(",");
            if (xy.length != 2) throw new IllegalArgumentException("bad point '" + pair + "' in \"" + points + "\"");
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("x", xy[0]);
            point.put("y", xy[1]);
            out.add(point);
        }
        return out;
    }

    private static Map<String, Object> tileSet(Element set) {
        Map<String, Object> out = attributes(set);
        out.put("type", "tileset");
        properties(set, out);
        image(child(set, "image"), out);
        Element offset = child(set, "tileoffset");
        if (offset != null) out.put("tileoffset", attributes(offset));
        List<Object> tiles = new ArrayList<>();
        for (Element tile : children(set, "tile")) tiles.add(tile(tile));
        if (!tiles.isEmpty()) out.put("tiles", tiles);
        return out;
    }

    private static Map<String, Object> tile(Element tile) {
        Map<String, Object> out = attributes(tile);
        properties(tile, out);
        image(child(tile, "image"), out);
        Element group = child(tile, "objectgroup");
        if (group != null) out.put("objectgroup", objectGroup(group));
        Element animation = child(tile, "animation");
        if (animation != null) {
            List<Object> frames = new ArrayList<>();
            for (Element frame : children(animation, "frame")) frames.add(attributes(frame));
            out.put("animation", frames);
        }
        return out;
    }

    private static Map<String, Object> template(Element template) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "template");
        Element set = child(template, "tileset");
        if (set != null) out.put("tileset", attributes(set));
        Element object = child(template, "object");
        if (object == null) throw new IllegalArgumentException("template has no <object>");
        out.put("object", object(object));
        return out;
    }

    /**
     * Copies an {@code <image>} element's attributes to the names the JSON format gives them.
     */
    private static void image(Element image, Map<String, Object> into) {
        if (image == null) return;
        if (!image.hasAttribute("source")) {
            throw new IllegalArgumentException("images embedded in the file aren't supported; save the image as a file");
        }
        into.put("image", image.getAttribute("source"));
        if (image.hasAttribute("width")) into.put("imagewidth", image.getAttribute("width"));
        if (image.hasAttribute("height")) into.put("imageheight", image.getAttribute("height"));
        if (image.hasAttribute("trans")) into.put("transparentcolor", image.getAttribute("trans"));
    }

    private static void properties(Element element, Map<String, Object> into) {
        Element properties = child(element, "properties");
        if (properties != null) into.put("properties", propertyList(properties));
    }

    private static List<Object> propertyList(Element properties) {
        List<Object> list = new ArrayList<>();
        for (Element property : children(properties, "property")) {
            Map<String, Object> p = attributes(property);
            if (!property.hasAttribute("value")) {
                Element members = child(property, "properties");
                if (members != null) {
                    p.put("value", propertyList(members)); // a class: its members, typed
                } else if (!"class".equals(property.getAttribute("type"))) {
                    p.put("value", property.getTextContent()); // a string over several lines
                }
            }
            list.add(p);
        }
        return list;
    }

    private static Map<String, Object> attributes(Element element) {
        Map<String, Object> out = new LinkedHashMap<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            out.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        return out;
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) return element;
        }
        return null;
    }

    /**
     * The child elements named {@code name}, or all of them for null, in order.
     */
    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && (name == null || element.getTagName().equals(name))) found.add(element);
        }
        return found;
    }
}
