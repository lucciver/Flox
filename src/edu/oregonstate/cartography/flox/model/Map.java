package edu.oregonstate.cartography.flox.model;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.GeometryFactory;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/**
 * A map contains a set of layers with symbolization.
 *
 * @author Bernhard Jenny, Cartography and Geovisualization Group, Oregon State
 * University
 */

//Every non static, non transient field in a JAXB-bound class will be 
//automatically bound to XML, unless annotated by @XmlTransient
@XmlAccessorType(XmlAccessType.FIELD)

public class Map {

    /**
     * The layers of this map.
     */
    private final ArrayList<Layer> layers = new ArrayList<>();

    public Map() {
    }

    int getNbrLayers() {
        return layers.size();
    }

    /**
     * Add a layer to the map.
     *
     * @param geometry the geometry to set
     * @return The new layer.
     */
    public Layer addLayer(Geometry geometry) {
        Layer layer = new Layer(geometry);
        layers.add(0, layer);
        return layer;
    }

    /**
     * Add a layer to the map.
     *
     * @param layer The layer to add.
     */
    public void addLayer(Layer layer) {
        layers.add(layer);
    }

    /**
     * Returns a layer specified by an id.
     *
     * @param id
     * @return
     */
    public Layer getLayer(int id) {
        return layers.get(id);
    }

    /**
     * Returns a layer by its name.
     * @param name Name must contain at least one non-empty character.
     * @return The found layer or null if non is found.
     */
    public Layer getLayer(String name) {
        if (name != null) {
            name = name.trim();
            if (name.length() > 0) {
                for (Layer layer : layers) {
                    if (name.equals(name)) {
                        return layer;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Deletes all current layers.
     */
    public void removeAllLayers() {
        layers.clear();
    }

    /**
     * Returns an iterator for all layers.
     *
     * @return
     */
    public Iterator<Layer> layerIterator() {
        return layers.iterator();
    }

    public Collection<Layer> getLayers() {
        return layers;
    }

    void removeLayer(int id) {
        layers.remove(id);
    }

    /**
     * Returns a OGC Simple Feature geometry collection with all geometry
     * features of the map.
     *
     * @return
     */
    public GeometryCollection getGeometryCollection() {
        ArrayList<Geometry> layerGeometries = new ArrayList<>();
        for (Layer layer : layers) {
            layerGeometries.add(layer.getGeometry());
        }
        Geometry[] geomArray = layerGeometries.toArray(new Geometry[0]);
        return new GeometryFactory().createGeometryCollection(geomArray);
    }

    /**
     * Returns the bounding box including all layers.
     *
     * @return
     */
    Rectangle2D getBoundingBox() {
        Rectangle2D bb = null;
        for (Layer layer : layers) {
            Rectangle2D layerBB = layer.getBoundingBox();
            if (layerBB != null) {
                if (bb == null) {
                    bb = layerBB;
                } else {
                    bb = bb.createUnion(layerBB);
                }
            }
        }
        return bb;
    }

}
