package edu.oregonstate.cartography.flox.gui;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import edu.oregonstate.cartography.flox.model.Flow;
import edu.oregonstate.cartography.flox.model.Layer;
import edu.oregonstate.cartography.flox.model.Model;
import edu.oregonstate.cartography.flox.model.Point;
import edu.oregonstate.cartography.flox.model.RangeboxEnforcer;
import edu.oregonstate.cartography.flox.model.VectorSymbol;
import edu.oregonstate.cartography.simplefeature.SimpleFeatureRenderer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

/**
 * A renderer for the Flox data model
 *
 * @author Bernhard Jenny, Cartography and Geovisualization Group, Oregon State
 * University
 */
public class FloxRenderer extends SimpleFeatureRenderer {

    /**
     * Color for drawing selected flows.
     */
    public static final Color SELECTION_COLOR = Color.decode("#59A4FF");

    /**
     * Color for drawing interior of nodes.
     */
    public static final Color NODE_FILL_COLOR = Color.WHITE;

    /**
     * Width of stroke line for nodes
     */
    public static final float NODE_STROKE_WIDTH = 2;

    /**
     * Radius of circles for control points (in pixels)
     */
    private static final double CR = 3;

    /**
     * The model containing all the map data
     */
    private final Model model;

    /**
     * Flag to indicate when the flow width is locked to the current map scale.
     */
    private boolean flowWidthLocked = false;

    /**
     * Creates a new renderer.
     *
     * @param model The model to render.
     * @param g2d The graphics context to render to.
     * @param west The left image border corresponds to this world coordinate
     * position.
     * @param north The top image border corresponds to this world coordinate
     * position.
     * @param scale The scale factor to apply when drawing.
     */
    public FloxRenderer(Model model, Graphics2D g2d,
            double west, double north, double scale) {
        super(g2d, west, north, scale);
        this.model = model;
    }

    public void render(boolean renderBackgroundLayers,
            boolean drawCanvas,
            boolean drawFlows,
            boolean drawNodes,
            boolean drawFlowRangebox,
            boolean drawControlPoints,
            boolean drawLineSegments,
            boolean drawLocks,
            boolean highlightSelected,
            boolean drawStartClipAreas,
            boolean drawEndClipAreas) {

        if (renderBackgroundLayers) {
            int nbrLayers = model.getNbrLayers();
            for (int i = nbrLayers - 1; i >= 0; i--) {
                Layer layer = model.getLayer(i);
                GeometryCollection geometry = layer.getGeometryCollection();
                VectorSymbol symbol = layer.getVectorSymbol();
                Color fillColor = symbol.isFilled() ? layer.getVectorSymbol().getFillColor() : null;
                Color strokeColor = symbol.isStroked() ? layer.getVectorSymbol().getStrokeColor() : null;
                if (fillColor != null || strokeColor != null) {
                    draw(geometry, fillColor, strokeColor);
                }
            }
        }

        if (drawCanvas) {
            drawCanvas();
        }

        if (drawFlows) {
            drawFlows(highlightSelected, drawLocks);
        }

        if (drawNodes) {
            drawNodes(highlightSelected, false);
        }

        if (drawFlowRangebox) {
            drawFlowRangebox();
        }

        if (drawControlPoints) {
            drawControlPoints();
        }

        if (drawLineSegments) {
            drawStraightLinesSegments();
        }

        if (drawStartClipAreas || drawEndClipAreas) {
            drawClipAreas(drawStartClipAreas, drawEndClipAreas);
        }
    }

    /**
     * Renders the flows to an image.
     *
     * @param model The flows to render.
     * @param maxDim The width or the height of the image will have this size.
     * @param bb The bounding box of the map area that will be visible in the
     * image
     * @param antialias If true anti-aliasing is applied.
     * @param drawBackgroundLayers If true, map layers are rendered.
     * @param fillNodes If true, nodes are filled.
     * @param drawSelectedFlows If false, selected flows are not drawn.
     * @param drawFlows If true, flows are rendered.
     * @param drawNodes If true, nodes are rendered.
     * @return The new image.
     */
    public static BufferedImage renderToImage(Model model, int maxDim,
            Rectangle2D bb,
            boolean antialias,
            boolean drawBackgroundLayers,
            boolean fillNodes,
            boolean drawSelectedFlows,
            boolean drawFlows,
            boolean drawNodes) {

        // find size of fitting image
        //Rectangle2D bb = model.getFlowsBoundingBox();
        double scale = maxDim / Math.max(bb.getWidth(), bb.getHeight());
        int w, h;
        if (bb.getWidth() > bb.getHeight()) {
            w = maxDim;
            h = (int) Math.ceil(bb.getHeight() * scale);
        } else {
            w = (int) Math.ceil(bb.getWidth() * scale);
            h = maxDim;
        }

        // create image
        BufferedImage bufferImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = bufferImage.createGraphics();

        // set default appearance of vector elements
        g2d.setStroke(new BasicStroke(1));
        g2d.setColor(Color.black);
        // erase background
        g2d.setBackground(Color.WHITE);
        g2d.clearRect(0, 0, w, h);

        // setup renderer
        FloxRenderer renderer = new FloxRenderer(model, g2d,
                bb.getMinX(), bb.getMaxY(), scale);
        FloxRenderer.enableHighQualityRenderingHints(g2d, antialias);
        renderer.render(drawBackgroundLayers,
                false, // drawCanvas
                drawFlows,
                drawNodes,
                false, // drawFlowRangebox, 
                false, // drawControlPoints 
                false, // drawLineSegments
                false, // drawLocks
                false, // highlightSelected
                false, // drawStartClipAreas
                false // drawEndClipAreas
        );

        return bufferImage;
    }

    /**
     * Constructs a GeneralPath object for drawing a Flow.
     *
     * @param flow The flow to convert in world coordinats.
     * @return A GeneralPath for drawing in pixel coordinates.
     */
    private GeneralPath flowToGeneralPath(Flow flow) {
        if (flow == null) {
            return null;
        }
        GeneralPath path = new GeneralPath();
        Point startPt = flow.getStartPt();
        path.moveTo(xToPx(startPt.x), yToPx(startPt.y));
        Point cPt = flow.getCtrlPt();
        Point endPt = flow.getEndPt();
        path.quadTo(xToPx(cPt.x), yToPx(cPt.y),
                xToPx(endPt.x), yToPx(endPt.y));
        return path;
    }

    /**
     * Computes clipping radius for an end node. Takes size of node and distance to
     * end node into account.
     *
     * @param endNode The end node of the flow.
     * @return Clipping radius in world coordinates.
     */
    private double endClipRadius(Point endNode) {
        // distance between end of flows and their end points
        double gapDistanceToEndNodes = model.getFlowDistanceFromEndPointPixel() / scale
                * getLockedScaleFactor();
        // Compute the radius of the end node (add stroke width / 2 to radius)
        double endNodeRadius = (NODE_STROKE_WIDTH / 2 + getNodeRadius(endNode)) / scale;
        return gapDistanceToEndNodes + endNodeRadius;
    }

    /**
     * Draws the flows to the Graphics2D context. Retrieves settings from the
     * model to determine flow width, length, as well as determining whether to
     * apply any clipping or add arrowheads.
     *
     * @param highlightSelected If true, selected flows are drawn with
     * SELECTION_COLOR.
     */
    private void drawFlows(boolean highlightSelected, boolean drawLocks) {

        double flowWidthScaleFactor = model.getFlowWidthScaleFactor();

        // Iterate through the flows
        Iterator<Flow> iterator = model.flowIterator();
        while (iterator.hasNext()) {
            Flow flow = iterator.next();

            // will create Swing paths for the flow line and the arrowhead
            GeneralPath flowPath;
            GeneralPath arrowPath = null;

            // Calculate the stroke width of the flow based on its value.
            // This is done here so that the Arrow class will have access to 
            // the stroke width.
            double flowStrokeWidth = Math.abs(flow.getValue()) * flowWidthScaleFactor
                    * getLockedScaleFactor();

            // Draw arrows if the model says so
            if (model.isDrawArrows()) {

                // Compute radius of clipping circle around end point.
                // Clip the flow with the clipping area and a circle around the end node
                flow = getClippedFlow(flow, endClipRadius(flow.getEndPt()));

                // Create an arrowhead
                Arrow arrow = new Arrow(flow, model, flowStrokeWidth, scale, west, north);

                // Get the GeneralPath needed to draw the arrowhead
                arrowPath = arrow.getArrowPath();

                // Get the clipped flow generated by the Arrow class. The 
                // Arrow class shortens the flow it was passed based on the
                // length of the arrowhead.
                flowPath = flowToGeneralPath(arrow.getOutFlow());
            } else {
                // Clip the flow with the clipping area
                double r = model.getFlowDistanceFromEndPointPixel() > 0 ? endClipRadius(flow.getEndPt()) : 0;
                flow = getClippedFlow(flow, r);
                flowPath = flowToGeneralPath(flow);
            }

            g2d.setColor(highlightSelected && flow.isSelected() ? SELECTION_COLOR : model.getFlowColor());

            // draw the arrow head
            if (model.isDrawArrows()) {
                g2d.fill(arrowPath);
            }

            // draw the flow
            g2d.setStroke(new BasicStroke((float) flowStrokeWidth,
                    BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g2d.draw(flowPath);

            // draw symbol for locked flows
            if (drawLocks && flow.isLocked()) {
                Point pt = flow.pointOnCurve(0.5);
                drawCross(pt.x, pt.y);
            }
        }

    }

    /**
     * Draw all nodes to a Graphics2D context.
     *
     * @param fillNodes If true, the node circles are filled with the stroke
     * color. Otherwise they are filled with white.
     */
    private void drawNodes(boolean highlightSelected, boolean fillNodes) {
        g2d.setStroke(new BasicStroke(NODE_STROKE_WIDTH));
        ArrayList<Point> nodes = model.getOrderedNodes(false);
        for (Point node : nodes) {
            double r = getNodeRadius(node);
            Color strokeColor = highlightSelected && node.isSelected()
                    ? SELECTION_COLOR : model.getFlowColor();
            Color fillColor = fillNodes ? model.getFlowColor() : NODE_FILL_COLOR;
            drawCircle(node.x, node.y, r, fillColor, strokeColor);
        }
    }

    /**
     * Draws the canvas.
     */
    private void drawCanvas() {
        g2d.setStroke(new BasicStroke(1));
        Rectangle2D canvas = model.getNodesBoundingBox();

        double cWidth = canvas.getWidth();
        double cHeight = canvas.getHeight();

        // Get the additional padding around the canvas, which is a
        // percentage of the current canvas specified by the model.
        double xPad = cWidth * model.getCanvasPadding();
        double yPad = cHeight * model.getCanvasPadding();

        // Calculate the points of the canvas rectangle, adding the 
        // canvasPadding.
        Point b1 = new Point(
                xToPx(canvas.getX() - xPad),
                yToPx(canvas.getY() - yPad));
        Point b2 = new Point(
                xToPx(canvas.getMaxX() + xPad),
                yToPx(canvas.getY() - yPad));
        Point b3 = new Point(
                xToPx(canvas.getX() - xPad),
                yToPx(canvas.getMaxY() + yPad));
        Point b4 = new Point(
                xToPx(canvas.getMaxX() + xPad),
                yToPx(canvas.getMaxY() + yPad));

        // Construct a GeneralPath from the canvas points
        GeneralPath canvasPath = new GeneralPath();
        canvasPath.moveTo(b1.x, b1.y);
        canvasPath.lineTo(b2.x, b2.y);
        canvasPath.lineTo(b4.x, b4.y);
        canvasPath.lineTo(b3.x, b3.y);
        canvasPath.lineTo(b1.x, b1.y);

        // Draw the canvas
        g2d.setColor(Color.BLACK);
        g2d.draw(canvasPath);
        g2d.setColor(new Color(245, 245, 245));
        g2d.fill(canvasPath);
    }

    private void drawFlowRangebox() {
        g2d.setStroke(new BasicStroke(1f));
        g2d.setColor(Color.GRAY);
        Iterator<Flow> iter = model.flowIterator();
        while (iter.hasNext()) {
            Flow flow = iter.next();
            if (flow.isSelected()) {
                RangeboxEnforcer enforcer = new RangeboxEnforcer(model);
                Point[] box = enforcer.computeRangebox(flow);

                Line2D line1 = new Line2D.Double(
                        xToPx(box[0].x), yToPx(box[0].y),
                        xToPx(box[1].x), yToPx(box[1].y));
                Line2D line2 = new Line2D.Double(
                        xToPx(box[2].x), yToPx(box[2].y),
                        xToPx(box[3].x), yToPx(box[3].y));
                Line2D line3 = new Line2D.Double(
                        xToPx(box[0].x), yToPx(box[0].y),
                        xToPx(box[2].x), yToPx(box[2].y));
                Line2D line4 = new Line2D.Double(
                        xToPx(box[1].x), yToPx(box[1].y),
                        xToPx(box[3].x), yToPx(box[3].y));

                g2d.draw(line1);
                g2d.draw(line2);
                g2d.draw(line3);
                g2d.draw(line4);
            }
        }
    }

    /**
     * Draw the control points of selected flows.
     */
    private void drawControlPoints() {
        // draw just the control points of selected flows.
        Iterator<Flow> iterator = model.flowIterator();
        while (iterator.hasNext()) {
            Flow flow = iterator.next();
            if (flow.isSelected()) {
                drawControlPoints(flow);
            }
        }
    }

    /**
     * Draw the control point of a Bezier curve and lines connecting the control
     * point to the start and end points.
     */
    private void drawControlPoints(Flow flow) {
        g2d.setStroke(new BasicStroke(1f));
        Point startPt = flow.getStartPt();
        Point endPt = flow.getEndPt();
        Point cpt = flow.getCtrlPt();
        g2d.setColor(Color.GRAY);
        Line2D line1 = new Line2D.Double(xToPx(startPt.x),
                yToPx(startPt.y), xToPx(cpt.x), yToPx(cpt.y));
        g2d.draw(line1);
        Line2D line2 = new Line2D.Double(xToPx(endPt.x),
                yToPx(endPt.y), xToPx(cpt.x), yToPx(cpt.y));
        g2d.draw(line2);
        if (cpt.isSelected()) {
            drawCircle(cpt.x, cpt.y, CR, Color.CYAN, Color.GRAY);
        } else {
            drawCircle(cpt.x, cpt.y, CR, Color.ORANGE, Color.GRAY);
        }
    }

    private void drawCross(double x, double y) {
        final double L = 4;

        Line2D line1 = new Line2D.Double(xToPx(x) - L, yToPx(y) - L,
                xToPx(x) + L, yToPx(y) + L);
        Line2D line2 = new Line2D.Double(xToPx(x) - L, yToPx(y) + L,
                xToPx(x) + L, yToPx(y) - L);

        g2d.setStroke(new BasicStroke(2f));
        g2d.setColor(Color.WHITE);
        g2d.draw(line1);
        g2d.draw(line2);

        g2d.setStroke(new BasicStroke(1f));
        g2d.setColor(Color.BLACK);
        g2d.draw(line1);
        g2d.draw(line2);
    }

    /**
     * Draw straight line segments for a Bezier curve. Useful for debugging.
     */
    private void drawStraightLinesSegments() {

        Iterator<Flow> iter = model.flowIterator();

        // If there are no flows, stop
        if (!iter.hasNext()) {
            return;
        }

        setStrokeWidth(2f);
        double deCasteljauTol = model.getDeCasteljauTolerance();

        while (iter.hasNext()) {
            Flow flow = iter.next();
            double r = model.getFlowDistanceFromEndPointPixel() > 0 ? endClipRadius(flow.getEndPt()) : 0;            
            ArrayList<Point> points = flow.toClippedStraightLineSegments(r, deCasteljauTol);
            for (Point point : points) {
                drawCircle(point.x, point.y, CR, Color.pink, Color.white);
            }
        }
    }

    private void drawClipAreas(boolean drawStartClipAreas, boolean drawEndClipAreas) {
        HashSet<Geometry> endClipAreas = new HashSet<>();
        HashSet<Geometry> startClipAreas = new HashSet<>();
        Iterator<Flow> iterator = model.flowIterator();
        while (iterator.hasNext()) {
            Flow flow = iterator.next();
            endClipAreas.add(flow.getEndClipArea());
            startClipAreas.add(flow.getStartClipArea());
        }

        if (drawStartClipAreas) {
            for (Geometry geometry : startClipAreas) {
                draw(geometry, null, Color.GRAY);
            }
        }

        if (drawEndClipAreas) {
            for (Geometry geometry : endClipAreas) {
                draw(geometry, null, Color.GRAY);
            }
        }
    }

    private double getLockedScaleFactor() {
        if (!model.isScaleLocked()) {
            return 1;
        } else {
            // compare the locked scale to the current scale
            double lockedMapScale = model.getLockedMapScale();
            return scale / lockedMapScale;
        }
    }

    private Flow getClippedFlow(Flow flow, double endClipRadius) {
        double deCasteljauTol = model.getDeCasteljauTolerance();
        return flow.getClippedFlow(endClipRadius, deCasteljauTol);
    }

    /**
     * Get a node's radius in pixels for drawing.
     *
     * @param node
     * @return
     */
    private double getNodeRadius(Point node) {
        double area = Math.abs(node.getValue()
                * model.getNodeSizeScaleFactor());
        return (Math.sqrt(area / Math.PI)) * getLockedScaleFactor();
    }

}
