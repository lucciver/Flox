package edu.oregonstate.cartography.flox.model;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollectionIterator;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.io.WKTWriter;
import static edu.oregonstate.cartography.flox.model.Circle.TOL;
import edu.oregonstate.cartography.utils.GeometryUtils;
import edu.oregonstate.cartography.utils.JTSUtils;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import net.jafama.FastMath;

/**
 * A flow based on a quadratic Bézier curve.
 *
 * @author Bernhard Jenny
 * @author Daniel Stephen
 */
//Every non static, non transient field in a JAXB-bound class will be 
//automatically bound to XML, unless annotated by @XmlTransient
@XmlAccessorType(XmlAccessType.FIELD)

public class Flow implements Comparable<Flow> {

    private static long idCounter = 0;

    // FIXME this does not guarantee a unique ID when flows are loaded from an XML file
    protected static long createID() {
        return idCounter++;
    }

    public enum FlowOffsettingQuality {
        LOW(3),
        HIGH(10);

        public final int iterations;

        FlowOffsettingQuality(int iterations) {
            this.iterations = iterations;
        }
    }

    /**
     * An identifier that is used to find Flows in a Graph. The id is not
     * required to be unique. For example, when a flow is split in two flows
     * both new flows have the same id as the original flow. However, a Graph
     * cannot contain multiple flows with identical IDs.
     */
    public final long id;

    /**
     * start point of flow.
     */
    private Point startPt;

    /**
     * end point of flow.
     */
    private Point endPt;

    /**
     * control point.
     */
    private double cPtX;
    private double cPtY;

    private boolean controlPointSelected;

    /**
     * mapped value.
     */
    private double value;

    /**
     * shorten end of flow by this distance (a radius around the end node) to
     * reduce overlaps with other flows and arrowheads
     */
    protected double endShorteningToAvoidOverlaps = 0;

    /**
     * shorten start of flow by this distance (a radius around the start node)
     * to reduce overlaps with other flows and arrowheads
     */
    protected double startShorteningToAvoidOverlaps = 0;

    /**
     * clip area for the start of the flow.
     */
    @XmlJavaTypeAdapter(GeometrySerializer.class)
    private Geometry startClipArea;

    /**
     * startClipArea serialized to WKT format
     */
    private String startClipAreaWKT;

    private double approximateStartAreaClipRadius;

    /**
     * clip area for the end of the flow.
     */
    @XmlJavaTypeAdapter(GeometrySerializer.class)
    private Geometry endClipArea;

    /**
     * endClipArea serialized to WKT format
     */
    private String endClipAreaWKT;

    private double approximateEndAreaClipRadius;

    /**
     * selection flag
     */
    private boolean selected = false;

    /**
     * Locked flag. Locked flows are not affected by forces, but still emit
     * forces onto other flows.
     */
    private boolean locked = false;

    /**
     * A cached approximation of the flow geometry by a straight polyline to
     * avoid repeated expensive conversions to a polyline.
     */
    private Point[] cachedPolyline;

    private Flow cachedClippedCurveIncludingArrow;

    /**
     * Construct a Flow from 3 points. Uses the passed id.
     *
     * @param startPt start point
     * @param ctrlX control point x
     * @param ctrlY control point y
     * @param endPt end point
     * @param value flow value
     * @param id id of the new Flow
     */
    public Flow(Point startPt, double ctrlX, double ctrlY, Point endPt, double value, long id) {
        assert (startPt != null);
        assert (endPt != null);
        assert (Double.isFinite(value));

        if (startPt.equals(endPt)) {
            throw new IllegalArgumentException("The start and end node of a flow cannot be identical.");
        }

        this.startPt = startPt;
        this.cPtX = ctrlX;
        this.cPtY = ctrlY;
        this.endPt = endPt;
        this.value = value;
        this.id = id;
    }

    /**
     * Construct a Flow from 3 points.
     *
     * @param startPt start point
     * @param ctrlX control point x
     * @param ctrlY control point y
     * @param endPt end point
     * @param value flow value
     */
    public Flow(Point startPt, double ctrlX, double ctrlY, Point endPt, double value) {
        this(startPt, ctrlX, ctrlY, endPt, value, createID());
    }

    /**
     * Construct a Flow from a start point and an end point.
     *
     * @param startPt Start point
     * @param endPt End point
     * @param value value of this flow
     */
    public Flow(Point startPt, Point endPt, double value) {
        this(startPt,
                (startPt.x + endPt.x) / 2, (startPt.y + endPt.y) / 2,
                endPt, value);
    }

    /**
     * Default constructor for JAXB
     */
    protected Flow() {
        this(new Point(), new Point(), Model.DEFAULT_FLOW_VALUE);
    }

    /**
     * Copy constructor. The new Flow has the same id as the passed Flow.
     * Creates deep copies of points. Creates shallow copies of clip areas.
     *
     * @param flow Flow to copy
     */
    public Flow(Flow flow) {
        this(new Point(flow.startPt),
                flow.cPtX, flow.cPtY,
                new Point(flow.endPt),
                flow.value,
                flow.id);
        shallowCopyClipAreas(flow, this);
        selected = flow.selected;
        locked = flow.locked;
        endShorteningToAvoidOverlaps = flow.getEndShorteningToAvoidOverlaps();
        startShorteningToAvoidOverlaps = flow.getStartShorteningToAvoidOverlaps();
    }

    /**
     * Returns a copy of this Flow. The id of the new Flow is identical to the
     * id of this Flow. Creates deep copies of points. Creates shallow copies of
     * clip areas.
     *
     * @return a copy
     */
    public Flow copyFlow() {
        return new Flow(this);
    }

    /**
     * Shallow-copies clip areas from one flow to another flow.
     *
     * @param src source flow
     * @param dst destination flow
     */
    final protected static void shallowCopyClipAreas(Flow src, Flow dst) {
        dst.startClipArea = src.startClipArea;
        dst.startClipAreaWKT = src.startClipAreaWKT;
        dst.approximateStartAreaClipRadius = src.approximateStartAreaClipRadius;
        dst.endClipArea = src.endClipArea;
        dst.endClipAreaWKT = src.endClipAreaWKT;
        dst.approximateEndAreaClipRadius = src.approximateEndAreaClipRadius;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName()).append(" ");
        sb.append("id=").append(id);
        sb.append(" start=").append(startPt);
        sb.append(", end=").append(endPt);
        sb.append(", ctrl=").append(new Point(cPtX, cPtY));
        sb.append(", value=").append(value);
        sb.append(", selected=").append(selected);
        sb.append(", locked=").append(locked);
        sb.append(", start point hash code=").append(startPt.hashCode());
        sb.append(", end point hash code=").append(endPt.hashCode());
        return sb.toString();
    }

    /**
     * Compares this flow to the specified flow for order. Compares values,
     * lengths, start point coordinates, and end point coordinates, in this
     * order. Returns a negative integer, zero, or a positive integer as this
     * object is less than, equal to, or greater than the specified object.
     *
     * @param flow flow to compare to
     * @return a negative integer, zero, or a positive integer as this object is
     * less than, equal to, or greater than the specified flow.
     */
    @Override
    public int compareTo(Flow flow) {
        if (flow == null) {
            throw new NullPointerException();
        }

        // call getValue to give overriding classes a chance to modify the returned value
        int i = Double.compare(getValue(), flow.getValue());

        // if values are identical, compare base lengths
        if (i == 0) {
            double dx1 = startPt.x - endPt.x;
            double dy1 = startPt.y - endPt.y;
            double l1 = dx1 * dx1 + dy1 * dy1;

            double dx2 = flow.startPt.x - flow.endPt.x;
            double dy2 = flow.startPt.y - flow.endPt.y;
            double l2 = dx2 * dx2 + dy2 * dy2;
            i = Double.compare(l1, l2);
        }

        // if values and base lengths are identical, compare lengths of convex hulls
        if (i == 0) {
            double dx1 = cPtX - endPt.x;
            double dy1 = cPtY - endPt.y;
            double dx2 = cPtX - startPt.x;
            double dy2 = cPtY - startPt.y;
            double l1 = dx1 * dx1 + dy1 * dy1 + dx2 * dx2 + dy2 * dy2;

            dx1 = flow.cPtX - flow.endPt.x;
            dy1 = flow.cPtY - flow.endPt.y;
            dx2 = flow.cPtX - flow.startPt.x;
            dy2 = flow.cPtY - flow.startPt.y;
            double l2 = dx1 * dx1 + dy1 * dy1 + dx2 * dx2 + dy2 * dy2;
            i = Double.compare(l1, l2);
        }

        // if values and lengths are identical, compare coordinates
        if (i == 0) {
            i = Double.compare(startPt.x, flow.startPt.x);
        }
        if (i == 0) {
            i = Double.compare(startPt.y, flow.startPt.y);
        }
        if (i == 0) {
            i = Double.compare(endPt.x, flow.endPt.x);
        }
        if (i == 0) {
            i = Double.compare(endPt.y, flow.endPt.y);
        }
        if (i == 0) {
            i = Double.compare(cPtX, flow.cPtX);
        }
        if (i == 0) {
            i = Double.compare(cPtY, flow.cPtY);
        }

        // if i equals 0, the two flows have the same values and start and end
        // at the same locations
        return i;
    }

    public void invalidateCachedValues() {
        cachedPolyline = null;
        cachedClippedCurveIncludingArrow = null;
    }

    public Point[] cachedPolyline(Model model) {
        if (cachedPolyline == null) {
            Flow clippedFlow = cachedClippedCurveIncludingArrow(model);
            ArrayList<Point> points = clippedFlow.regularIntervals(model.segmentLength());
            cachedPolyline = points.toArray(new Point[points.size()]);
        }
        return cachedPolyline;
    }

    public Flow cachedClippedCurveIncludingArrow(Model model) {
        if (cachedClippedCurveIncludingArrow == null) {
            cachedClippedCurveIncludingArrow = model.clipFlowForComputations(this);
        }
        return cachedClippedCurveIncludingArrow;
    }

    public Point[] cachedClippedPolylineIncludingArrow(Model model) {
        return cachedClippedCurveIncludingArrow(model).cachedPolyline(model);
    }

    /**
     * Returns true if this flow intersects with an obstacle.
     *
     * @param obstacle obstacle
     * @param model data model
     * @param minObstacleDistPx minimum empty space between flow and obstacles
     * (in pixels)
     * @return
     */
    protected boolean cachedClippedCurveIncludingArrowIntersectsObstacle(Obstacle obstacle, Model model, int minObstacleDistPx) {
        double tol = 1d / model.getReferenceMapScale(); // 1 pixel in world coordinates

        // flow width in world coordinates
        double strokeWidthWorld = model.getFlowWidthPx(this) / model.getReferenceMapScale();

        // obstacle radius is in world coordinates
        // add minimum obstacle distance
        double obstacleRadiusWorld = obstacle.r
                + minObstacleDistPx / model.getReferenceMapScale();

        // the minimum distance between the obstacle center and the flow axis
        double minDist = (strokeWidthWorld / 2) + obstacleRadiusWorld;

        // test with flow bounding box
        // extend bounding box by minDist.
        Rectangle2D flowBB = getBoundingBox();
        flowBB.add((flowBB.getMinX() - minDist), (flowBB.getMinY() - minDist));
        flowBB.add((flowBB.getMaxX() + minDist), (flowBB.getMaxY() + minDist));

        // the obstacle's circle center must be inside the extended bounding box
        if (flowBB.contains(obstacle.x, obstacle.y) == false) {
            return false;
        }

        // Check the shortest distance between the obstacle and the flow. If it's 
        // less than the minimum distance, then the flow intersects the obstacle. 
        // FIXME test for beyondButtCaps ?
        double shortestDistSquare = distanceSquare(obstacle.x, obstacle.y, tol);
        return shortestDistSquare < minDist * minDist;
    }

    /**
     * Tests whether this Flow intersects with another Flow. This is an
     * approximate test.
     *
     * @param flow flow to detect intersection with
     * @param model data model
     * @return true if the two flows intersect
     */
    public boolean cachedClippedCurveIncludingArrowIntersects(Flow flow, Model model) {
        Point[] thisPolyline = cachedClippedPolylineIncludingArrow(model);
        Point[] thatPolyline = flow.cachedClippedPolylineIncludingArrow(model);
        return polylinesIntersect(thisPolyline, thatPolyline);
    }

    /**
     * Tests whether this Flow intersects with another FlowPair. This is an
     * approximate test.
     *
     * @param flowPair FlowPair to detect intersection with
     * @param model data model
     * @return true if the two flows intersect
     */
    public boolean cachedClippedCurvedIncludingArrowIntersects(FlowPair flowPair, Model model) {
        return flowPair.cachedClippedCurveIncludingArrowIntersects(this, model);
    }

    /**
     * Test whether two polylines intersect.
     *
     * @param polyline1
     * @param polyline2
     * @return
     */
    static protected boolean polylinesIntersect(Point[] polyline1, Point[] polyline2) {
        for (int i = 0; i < polyline1.length - 1; i++) {
            double x1 = polyline1[i].x;
            double y1 = polyline1[i].y;
            double x2 = polyline1[i + 1].x;
            double y2 = polyline1[i + 1].y;
            for (int j = 0; j < polyline2.length - 1; j++) {
                double x3 = polyline2[j].x;
                double y3 = polyline2[j].y;
                double x4 = polyline2[j + 1].x;
                double y4 = polyline2[j + 1].y;
                if (GeometryUtils.linesIntersect(x1, y1, x2, y2, x3, y3, x4, y4)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the start point of the flow.
     *
     * @return the startPt
     */
    public Point getStartPt() {
        return startPt;
    }

    /**
     * Set the start point of the flow.
     *
     * @param startPt the startPt to set
     */
    public void setStartPt(Point startPt) {
        this.startPt = startPt;
    }

    /**
     * Returns the end point of the flow.
     *
     * @return the endPt
     */
    public Point getEndPt() {
        return endPt;
    }

    /**
     * Set the end point of the flow.
     *
     * @param endPt the endPt to set
     */
    public void setEndPt(Point endPt) {
        this.endPt = endPt;
    }

    /**
     * If the start point is passed, returns the end point, and vice versa.
     *
     * @param point start or end point of this flow
     * @return opposite point or null if neither the start nor the end point was
     * passed.
     */
    public Point getOppositePoint(Point point) {
        if (point == startPt) {
            return endPt;
        } else if (point == endPt) {
            return startPt;
        }
        return null;
    }

    /**
     * Returns the distance between start and end point.
     *
     * @return distance between start point and end point
     */
    public double getBaselineLength() {
        double dx = startPt.x - endPt.x;
        double dy = startPt.y - endPt.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Returns the squared distance between start and end point.
     *
     * @return squared distance between start point and end point
     */
    public double getBaselineLengthSquare() {
        double dx = startPt.x - endPt.x;
        double dy = startPt.y - endPt.y;
        return dx * dx + dy * dy;
    }

    /**
     * Returns whether the trunk of the flow is longer than a minimum length.
     * The trunk is the visible piece of the flow without the arrowhead.
     *
     * This is an approximation. Instead of computing the Bezier curve length,
     * the distance between the start point and the end point of the trunk are
     * used.
     *
     * @param minLength the minimum length
     * @param model model with all flows
     * @return true if this flow is longer than the passed minLength
     */
    public boolean isFlowTrunkLongerThan(double minLength, Model model) {
        Flow flowTrunk = model.clipFlow(this, true, true, true);
        return flowTrunk.getBaselineLengthSquare() > minLength * minLength;
    }

    /**
     * Returns the ratio between the length of the base line of flow trunk (that
     * is, the flow without the arrowhead) and the length of the visible flow
     * including the arrowhead.
     *
     * @param model model
     * @return ratio between 0 (there is no trunk or the flow has a length of 0)
     * to 1 (there is no arrowhead).
     */
    public double flowTrunkToFlowRatio(Model model) {
        if (model.isDrawArrowheads() == false) {
            return 1d;
        } else {
            Flow flowTrunk = model.clipFlow(this, true, true, true);
            double l1 = flowTrunk.getBaselineLength();
            Flow clippedFlowWithArrowhead = model.clipFlow(this, false, true, true);
            double l2 = clippedFlowWithArrowhead.getBaselineLength();
            if (l2 == 0) {
                return 0;
            }
            return l1 / l2;
        }
    }

    /**
     * Returns the orientation angle of the line connecting the start point and
     * the end point
     *
     * @return Angle in radians, counter-clockwise, 0 is pointing eastwards
     */
    public double getBaselineOrientation() {
        final double dx = endPt.x - startPt.x;
        final double dy = endPt.y - startPt.y;
        return FastMath.atan2(dy, dx);
    }

    /**
     * Returns a point between the start point and the end point.
     *
     * @return the mid-point
     */
    public Point getBaseLineMidPoint() {
        return new Point((endPt.x + startPt.x) / 2, (endPt.y + startPt.y) / 2);
    }

    /**
     * Computes the square distance between a passed point and the point between
     * start and end points.
     *
     * @param x x point
     * @param y y point
     * @return distance between base line mid point and x/y
     */
    public double getSquareDistanceToBaseLineMidPoint(double x, double y) {
        double midX = (endPt.x + startPt.x) / 2d;
        double midY = (endPt.y + startPt.y) / 2d;
        double dx = x - midX;
        double dy = y - midY;
        return dx * dx + dy * dy;
    }

    /**
     * Projects a point onto the base line and returns the projected length
     * relative to the mid point of the base line. The base line connects start
     * and end points.
     *
     * @param x x point
     * @param y y point
     * @return length of vector between the base line mid point and x/y
     */
    public double scalarProjectionOnBaselineRelativeToMidPoint(double x, double y) {
        double midX = (endPt.x + startPt.x) / 2d;
        double midY = (endPt.y + startPt.y) / 2d;
        // vector A from mid point to passed point
        double ax = x - midX;
        double ay = y - midY;
        // vector B from mid point to end point
        double bx = endPt.x - midX;
        double by = endPt.y - midY;
        // scalar product of A and B, divided by length of B
        return (ax * bx + ay * by) / Math.sqrt(bx * bx + by * by);
    }

    /**
     * Projects a point onto the base line and returns the distance between the
     * start point and the projected point. The base line connects start and end
     * points.
     *
     * @param x x point
     * @param y y point
     * @return length of vector between the base line mid point and x/y
     */
    public double scalarProjectionOnBaseline(double x, double y) {
        // vector A from start point to passed point
        double ax = x - startPt.x;
        double ay = y - startPt.y;
        // vector B from start point to end point
        double bx = endPt.x - startPt.x;
        double by = endPt.y - startPt.y;
        // scalar product of A and B, divided by length of the base line
        return (ax * bx + ay * by) / Math.sqrt(bx * bx + by * by);
    }

    /**
     * Inverse start and end point.
     *
     */
    public void reverseFlow() {
        Point tempPt = startPt;
        startPt = endPt;
        endPt = tempPt;

        // swap clipping geometry
        Geometry tempGeometry = startClipArea;
        startClipArea = endClipArea;
        endClipArea = tempGeometry;

        // swap clipping WKT geometry
        String tempWKT = startClipAreaWKT;
        startClipAreaWKT = endClipAreaWKT;
        endClipAreaWKT = tempWKT;

        // swap area clip radii
        double tempR = approximateStartAreaClipRadius;
        approximateStartAreaClipRadius = approximateEndAreaClipRadius;
        approximateEndAreaClipRadius = tempR;

        // swap shortening values
        // these values should be recomputed
        double temp = endShorteningToAvoidOverlaps;
        startShorteningToAvoidOverlaps = endShorteningToAvoidOverlaps;
        endShorteningToAvoidOverlaps = temp;
    }

    /**
     * Returns the mapped value.
     *
     * @return the flow value
     */
    public double getValue() {
        return value;
    }

    /**
     * Change the flow value. <STRONG>Important: Must be followed by a call to
     * Graph.updateCachedValues().</STRONG>
     *
     * @param value the value to set
     */
    protected void setValue(double value) {
        assert (Double.isFinite(value));
        this.value = value;
    }

    /**
     * @return the startClipArea
     */
    public Geometry getStartClipArea() {
        return startClipArea;
    }

    /**
     * @param startClipArea the startClipArea to set
     */
    public final void setStartClipArea(Geometry startClipArea) {
        this.startClipArea = startClipArea;
        if (startClipArea != null) {
            startClipAreaWKT = new WKTWriter().write(startClipArea);

            // compute clip radius for area from straight line connecting start and end point
            ArrayList<Point> line = new ArrayList<>();
            line.add(startPt);
            line.add(endPt);
            LineString lineString = JTSUtils.pointsToLineString(line);
            approximateStartAreaClipRadius = maskClippingRadius(lineString, true);
        } else {
            startClipAreaWKT = null;
            approximateStartAreaClipRadius = 0;
        }
    }

    /**
     * @return the endClipArea
     */
    public Geometry getEndClipArea() {
        return endClipArea;
    }

    /**
     * @param endClipArea the endClipArea to set
     */
    public final void setEndClipArea(Geometry endClipArea) {
        this.endClipArea = endClipArea;
        if (endClipArea != null) {
            this.endClipAreaWKT = new WKTWriter().write(endClipArea);

            // compute clip radius for area from straight line connecting start and end point
            ArrayList<Point> line = new ArrayList<>();
            line.add(startPt);
            line.add(endPt);
            LineString lineString = JTSUtils.pointsToLineString(line);
            approximateEndAreaClipRadius = maskClippingRadius(lineString, false);

        } else {
            endClipAreaWKT = null;
            approximateEndAreaClipRadius = 0;
        }
    }

    /**
     * @return the selected
     */
    public boolean isSelected() {
        return selected;
    }

    /**
     * @param selected the selected to set
     */
    final public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /**
     * @return the locked
     */
    public boolean isLocked() {
        return locked;
    }

    /**
     * @param locked the locked to set
     */
    final public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /**
     * Creates a straight flow line by placing the control between the start
     * point and the end point.
     */
    public void straighten() {
        cPtX = (startPt.x + endPt.x) / 2;
        cPtY = (startPt.y + endPt.y) / 2;
    }

    /**
     * Returns a bounding box containing the curve. The control point can be
     * outside of the bounding box returned by this method. Does not take any
     * line width into account. Based on
     * http://pomax.github.io/bezierinfo/#boundingbox
     *
     * @return a new rectangular bounding box.
     */
    public Rectangle2D.Double getBoundingBox() {
        // initialize bounding box with start and end points
        double xmin, xmax, ymin, ymax;
        if (startPt.x > endPt.x) {
            xmin = endPt.x;
            xmax = startPt.x;
        } else {
            xmin = startPt.x;
            xmax = endPt.x;
        }
        if (startPt.y > endPt.y) {
            ymin = endPt.y;
            ymax = startPt.y;
        } else {
            ymin = startPt.y;
            ymax = endPt.y;
        }

        // Compute parameter t for the root of the first derivative of the x 
        // position. This is the t parameter for the extremum in x of the curve, 
        // as the first derivative is 0 at the extremum.
        double tx = (startPt.x - cPtX) / (startPt.x - 2 * cPtX + endPt.x);
        // t must be in [0,1]
        if (Double.isFinite(tx) && tx >= 0 && tx <= 1) {
            double one_minus_tx = 1d - tx;
            // compute x position of extrema
            double x = one_minus_tx * one_minus_tx * startPt.x
                    + 2 * one_minus_tx * tx * cPtX + tx * tx * endPt.x;
            // extend bounding box
            xmin = Math.min(xmin, x);
            xmax = Math.max(xmax, x);
        }

        // repeat for y
        double ty = (startPt.y - cPtY) / (startPt.y - 2 * cPtY + endPt.y);
        if (Double.isFinite(ty) && ty >= 0 && ty <= 1) {
            double one_minus_ty = 1d - ty;
            double y = one_minus_ty * one_minus_ty * startPt.y
                    + 2 * one_minus_ty * ty * cPtY + ty * ty * endPt.y;
            ymin = Math.min(ymin, y);
            ymax = Math.max(ymax, y);
        }

        return new Rectangle2D.Double(xmin, ymin, xmax - xmin, ymax - ymin);
    }

    public double cPtX() {
        return cPtX;
    }

    public double cPtY() {
        return cPtY;
    }

    public void setCtrlPt(double x, double y) {
        cPtX = x;
        cPtY = y;
        invalidateCachedValues();
    }

    /**
     * Returns a control point location that results in perfectly symmetric
     * flow. The perpendicular distance to the line connecting start and end
     * point is identical to the distance of this flow.
     *
     * @return a point with the control point location resulting in a symmetric
     * flow
     */
    public Point getSymmetricControlPoint() {
        // mid point between start and end points
        double mx = (endPt.x + startPt.x) / 2d;
        double my = (endPt.y + startPt.y) / 2d;

        //  normal vector on base line with length 1
        double dx = endPt.x - startPt.x;
        double dy = endPt.y - startPt.y;
        double nx = -dy;
        double ny = dx;
        double l = Math.sqrt(nx * nx + ny * ny);
        nx /= l;
        ny /= l;

        // vertical distance of control point to base line (using cross product)
        double startToCtrlX = cPtX - startPt.x;
        double startToCtrlY = cPtY - startPt.y;
        double a2 = (dx * startToCtrlY - dy * startToCtrlX) / l;

        // place control point at same vertical distance on a line perpendicular 
        // to the base line passing through the mid point.
        double x = mx + a2 * nx;
        double y = my + a2 * ny;
        return new Point(x, y);
    }

    /**
     * Move control point.
     *
     * @param dx horizontal offset
     * @param dy vertical offset
     */
    public void offsetCtrlPt(double dx, double dy) {
        cPtX += dx;
        cPtY += dy;
        invalidateCachedValues();
    }

    public boolean isControlPointSelected() {
        return controlPointSelected;
    }

    public void setControlPointSelected(boolean selected) {
        controlPointSelected = selected;
    }

    /**
     * Returns the slope at curve parameter t.
     *
     * @param t t parameter, must be within 0 and 1.
     * @return slope in radians
     */
    public double getSlope(double t) {
        assert (t >= 0 && t <= 1);
        double dx = (1 - t) * (cPtX - startPt.x) + t * (endPt.x - cPtX);
        double dy = (1 - t) * (cPtY - startPt.y) + t * (endPt.y - cPtY);
        return Math.atan2(dy, dx);
    }

    /**
     * Computes the normal vector on this flow at curve parameter t. The vector
     * is normalized. The normal points to the left when viewed from the start
     * towards the end point.
     *
     * @param t t parameter, must be within 0 and 1.
     * @return normal vector
     */
    public double[] getNormal(double t) {
        assert (t >= 0 && t <= 1);
        double dx = (1 - t) * (cPtX - startPt.x) + t * (endPt.x - cPtX);
        double dy = (1 - t) * (cPtY - startPt.y) + t * (endPt.y - cPtY);
        double l = Math.sqrt(dx * dx + dy * dy);
        return new double[]{-dy / l, dx / l};
    }

    /**
     * Test whether the tangent at parameter t intersects the end node.
     *
     * @param t curve parameter t between 0 and 1
     * @param nodeRadius radius of start or end node in world coordinates
     * @return True if the tangent and the circle around the start or end node
     * with radius r intersect; false otherwise.
     */
    public boolean isTangentDirectedTowardsEndNode(double t, double nodeRadius) {
        return isTangentDirectedTowardsNode(t, nodeRadius, true);
    }

    /**
     * Test whether the tangent at parameter t intersects the start node.
     *
     * @param t curve parameter t between 0 and 1
     * @param nodeRadius radius of start or end node in world coordinates
     * @return True if the tangent and the circle around the start or end node
     * with radius r intersect; false otherwise.
     */
    public boolean isTangentDirectedTowardsStartNode(double t, double nodeRadius) {
        return isTangentDirectedTowardsNode(t, nodeRadius, false);
    }

    /**
     * Test whether the tangent at parameter t intersects the start or the end
     * node.
     *
     * @param t curve parameter t between 0 and 1
     * @param r radius of start or end node in world coordinates
     * @param endNode if true, test with the end node; if false, test with the
     * start node.
     * @return True if the tangent and the circle around the start or end node
     * with radius r intersect; false otherwise.
     */
    private boolean isTangentDirectedTowardsNode(double t, double nodeRadius, boolean endNode) {
        assert (t >= 0d && t <= 1d);

        // point on curve at paramter t
        double w3 = t * t;
        double _1_t = 1 - t;
        double w2 = 2 * _1_t * t;
        double w1 = _1_t * _1_t;
        double px = startPt.x * w1 + cPtX * w2 + endPt.x * w3;
        double py = startPt.y * w1 + cPtY * w2 + endPt.y * w3;

        // first derivative: direction of tangent vector at parameter t
        double dx = _1_t * (cPtX - startPt.x) + t * (endPt.x - cPtX);
        double dy = _1_t * (cPtY - startPt.y) + t * (endPt.y - cPtY);
        double l = Math.sqrt(dx * dx + dy * dy);
        dx /= l;
        dy /= l;

        // invert tangent if testing with start node
        if (endNode == false) {
            dx = -dx;
            dy = -dy;
        }

        Point n = endNode ? getEndPt() : getStartPt();
        return GeometryUtils.circleAndRayIntersect(n.x, n.y, nodeRadius, px, py, dx, dy);
    }

    /**
     * Offsets this flow in parallel to the line. This changes the start, end
     * and control point locations.
     *
     * @param offset offset distance
     * @param model data model
     * @param quality
     */
    public void offsetFlow(double offset, Model model, FlowOffsettingQuality quality) {
        if (offset == 0d) {
            return;
        }

        // number of iterations
        final int nbrIterations = quality.iterations;
        // number of samples along the curves for computing distances
        final int nbrTSamples = 20;
        // heuristic weight for moving along the offset curve
        final double w1 = 0.8;
        // heuristic weight for moving along the original curve
        final double w2 = 1 - w1;

        assert (Double.isFinite(offset));

        // coordinates of offset flow
        double startX, startY, ctrlPtX, ctrlPtY, endX, endY;

        // The offset start and end points are not on a normal vector through 
        // the start and end points of the flow. Instead, the normal vector for 
        // the location where the flow touches the node is used. This results in 
        // nicer parallel flows.
        // compute t paramter for computing the normal for the flow end
        double endNodeRadiusPx = model.getNodeStrokeWidthPx() / 2 + model.getNodeRadiusPx(getEndPt());
        double gapDistanceToEndNodesPx = model.getFlowDistanceFromEndPointPixel();
        double endNodeClipRadius = (gapDistanceToEndNodesPx + endNodeRadiusPx) / model.getReferenceMapScale();
        double endT = getIntersectionTWithCircleAroundEndPoint(endNodeClipRadius);

        // offset the end point
        double[] endNormal = getNormal(endT);
        endX = endPt.x + endNormal[0] * offset;
        endY = endPt.y + endNormal[1] * offset;

        // compute t paramter for computing the normal for the flow start
        double startNodeRadiusPx = model.getNodeStrokeWidthPx() / 2 + model.getNodeRadiusPx(getStartPt());
        double gapDistanceToStartNodesPx = model.getFlowDistanceFromStartPointPixel();
        double startNodeClipRadius = (gapDistanceToStartNodesPx + startNodeRadiusPx) / model.getReferenceMapScale();
        double startT = getIntersectionTWithCircleAroundStartPoint(startNodeClipRadius);

        // offset the start point
        double[] startNormal = getNormal(startT);
        startX = startPt.x + startNormal[0] * offset;
        startY = startPt.y + startNormal[1] * offset;

        // construct control point position. The initial geometry of the new start point, 
        // end point and control point are identical to the original geometry, 
        // but are scaled and rotated.
        // first compute direction vector of current baseline with length 1
        double dx1 = startPt.x - endPt.x;
        double dy1 = startPt.y - endPt.y;
        double baselineLength1 = Math.sqrt(dx1 * dx1 + dy1 * dy1);
        dx1 /= baselineLength1;
        dy1 /= baselineLength1;
        // then compute direction vector of new baseline with length 1
        double dx2 = startX - endX;
        double dy2 = startY - endY;
        double baselineLength2 = Math.sqrt(dx2 * dx2 + dy2 * dy2);
        dx2 /= baselineLength2;
        dy2 /= baselineLength2;
        // cross product for computing sin of rotation angle between the baselines
        double sinRot = dx1 * dy2 - dy1 * dx2;
        // dot product for computing cos of rotation angle between the baselines
        double cosRot = dx1 * dx2 + dy1 * dy2;
        // place control point
        double scale = baselineLength2 / baselineLength1;
        double newX = scale * (cPtX - startPt.x);
        double newY = scale * (cPtY - startPt.y);
        newX = newX * cosRot - newY * sinRot;
        newY = newX * sinRot + newY * cosRot;
        ctrlPtX = newX + startX;
        ctrlPtY = newY + startY;

        // improve the control point position such that the distance between the
        // original curve and the offset curve are approximately constant along 
        // the two curves
//        Point[] offsetPts = new Point[nbrTSamples];
//        Point[] originalPts = new Point[nbrTSamples];
        for (int i = 0; i < nbrIterations; i++) {
            Flow offsetFlow = new Flow(new Point(startX, startY),
                    ctrlPtX, ctrlPtY,
                    new Point(endX, endY), 1d);
            double moveX = 0;
            double moveY = 0;

            /*    
            // compute points along the two curves
            for (int j = 0; j < nbrTSamples; j++) {
                double t = (j + 0.5) * (1. / nbrTSamples);
                offsetPts[j] = offsetFlow.pointOnCurve(t);
                originalPts[j] = pointOnCurve(t);
            }
            
            // move along the offset curve and for each point find the closest 
            // point on the original curve
            for (int offsetID = 0; offsetID < nbrTSamples; offsetID++) {
                double minDistSqr = Double.MAX_VALUE;
                Point offsetPt = offsetPts[offsetID];
                int closestOriginalID = 0;
                for (int originalID = 0; originalID < nbrTSamples; originalID++) {
                    Point originalPt = originalPts[originalID];
                    double distSqr = offsetPt.distanceSqr(originalPt);
                    if (distSqr < minDistSqr) {
                        closestOriginalID = originalID;
                        minDistSqr = distSqr;
                    }
                }

                // compute direction and length of displacement force for closest point
                double dirX = offsetPt.x - originalPts[closestOriginalID].x;
                double dirY = offsetPt.y - originalPts[closestOriginalID].y;
                double curveDistance = Math.sqrt(dirX * dirX + dirY * dirY);
                double gap = Math.abs(offset) - curveDistance;
                double gapW1 = gap * w1;
                if (curveDistance != 0d) {
                    dirX /= curveDistance;
                    dirY /= curveDistance;
                    moveX += dirX * gapW1;
                    moveY += dirY * gapW1;
                }
            }

            // move along the original curve and for each point find the closest 
            // point on the offset curve
            for (int originalID = 0; originalID < nbrTSamples; originalID++) {
                double minDistSqr = Double.MAX_VALUE;
                Point originalPt = originalPts[originalID];
                int closestOffsetID = 0;
                for (int offsetID = 0; offsetID < nbrTSamples; offsetID++) {
                    Point offsetPt = offsetPts[offsetID];
                    double distSqr = offsetPt.distanceSqr(originalPt);
                    if (distSqr < minDistSqr) {
                        closestOffsetID = offsetID;
                        minDistSqr = distSqr;
                    }
                }
                
                // compute direction and length of displacement force for closest point
                double dirX = originalPt.x - offsetPts[closestOffsetID].x;
                double dirY = originalPt.y - offsetPts[closestOffsetID].y;
                double curveDistance = Math.sqrt(dirX * dirX + dirY * dirY);
                double gap = Math.abs(offset) - curveDistance;
                double gapW2 = gap * w2;
                if (curveDistance > 0) {
                    dirX /= curveDistance;
                    dirY /= curveDistance;
                    moveX -= dirX * gapW2;
                    moveY -= dirY * gapW2;
                }
            }
             */
            // an alternative method that computes exact distances FIXME compare speed
            for (int j = 0; j < nbrTSamples; j++) {
                double t = (j + 0.5) * (1. / nbrTSamples);

                // Move along the offset curve and find closest points on the original curve.
                // This results in a curve without excessive curvature, but 
                // the offset curve is too distant from the original curve where 
                // the original curve is strongly bent
                Point ptOnOffsetCurve1 = offsetFlow.pointOnCurve(t);
                //Point ptOnOriginalCurve1 = closestPointOnCurve(ptOnOffsetCurve1);
                Point ptOnOriginalCurve1 = closestPointOnCurve(t, ptOnOffsetCurve1);
                //Point ptOnOriginalCurve1 = closestPointOnCurve(ptOnOffsetCurve1);
                double curveDistance1 = ptOnOffsetCurve1.distance(ptOnOriginalCurve1);
                double gap1 = Math.abs(offset) - curveDistance1;
                double gapW1 = gap1 * w1;

                // direction of control point movement
                double dirX1 = ptOnOffsetCurve1.x - ptOnOriginalCurve1.x;
                double dirY1 = ptOnOffsetCurve1.y - ptOnOriginalCurve1.y;
                double dCtrl1 = Math.sqrt(dirX1 * dirX1 + dirY1 * dirY1);
                if (dCtrl1 > 0) { // FIXME test not needed
                    dirX1 /= dCtrl1;
                    dirY1 /= dCtrl1;
                    moveX += dirX1 * gapW1;
                    moveY += dirY1 * gapW1;
                }

                // Move along the offset curve and find closest points on the original curve.
                // This results in a curve with approximately correct distance to the original curve,
                // but curves tend to by curvy where the original curve is strongly bent
                Point ptOnOriginalCurve2 = pointOnCurve(t);
                Point ptOnOffsetCurve2 = offsetFlow.closestPointOnCurve(t, ptOnOriginalCurve2);
                //Point ptOnOffsetCurve2 = offsetFlow.closestPointOnCurve(ptOnOriginalCurve2);
                double curveDistance2 = ptOnOffsetCurve2.distance(ptOnOriginalCurve2);
                double gap2 = Math.abs(offset) - curveDistance2;
                double gapW2 = gap2 * w2;

                // direction of control point movement
                double dirX2 = ptOnOffsetCurve2.x - ptOnOriginalCurve2.x;
                double dirY2 = ptOnOffsetCurve2.y - ptOnOriginalCurve2.y;
                double dCtrl2 = Math.sqrt(dirX2 * dirX2 + dirY2 * dirY2);
                if (dCtrl2 > 0) {
                    dirX2 /= dCtrl2;
                    dirY2 /= dCtrl2;
                    moveX += dirX2 * gapW2;
                    moveY += dirY2 * gapW2;
                }
            }
            // move control point
            moveX /= nbrTSamples;
            moveY /= nbrTSamples;
            ctrlPtX += moveX;
            ctrlPtY += moveY;
        }

        // copy new geometry to this flow
        startPt.x = startX;
        startPt.y = startY;
        endPt.x = endX;
        endPt.y = endY;
        cPtX = ctrlPtX;
        cPtY = ctrlPtY;
    }

    /**
     * Constructs a GeneralPath object for drawing the Flow.
     *
     * @param scale scale factor for converting from world to pixel coordinates
     * @param west horizontal origin in world coordinates
     * @param north vertical origin in world coordinates
     * @return A GeneralPath for drawing in pixel coordinates.
     */
    public GeneralPath toGeneralPath(double scale, double west, double north) {
        GeneralPath path = new GeneralPath();
        Point pt0 = getStartPt();
        path.moveTo((pt0.x - west) * scale, (north - pt0.y) * scale);
        Point pt2 = getEndPt();
        path.quadTo((cPtX - west) * scale, (north - cPtY) * scale,
                (pt2.x - west) * scale, (north - pt2.y) * scale);
        return path;
    }

    /**
     * Returns the location on the Bézier curve at parameter value t.
     *
     * @param t Parameter [0..1]
     * @return Location on curve.
     */
    public Point pointOnCurve(double t) {
        //assert (t >= 0d && t <= 1d); // FIXME
        if (Double.isFinite(t) == false) {
            System.out.println(t); // FIXME
            assert (t >= 0d && t <= 1d);
        }
        double w3 = t * t;
        double _1_t = 1 - t;
        double w2 = 2 * _1_t * t;
        double w1 = _1_t * _1_t;
        double x = startPt.x * w1 + cPtX * w2 + endPt.x * w3;
        double y = startPt.y * w1 + cPtY * w2 + endPt.y * w3;
        return new Point(x, y, t);
    }

    /**
     * Split a flow into two new flows. The new flows have the same id, value,
     * selection and lock state as this flow. The split flows have new start,
     * end, and control points. The first flow has the same start clip area as
     * this flow. The second flow has the same end clip area as this flow.
     *
     * Maths based on http://pomax.github.io/bezierinfo/#matrixsplit
     *
     * @param t Parametric position [0..1]
     * @return Two new flows if tx is > 0 and tx < 1. Otherwise two references
     * to this.
     */
    public Flow[] split(double t) {
        if (t <= 0 || t >= 1) {
            return new Flow[]{this, this};
        }

        double startX1 = startPt.x;
        double startY1 = startPt.y;
        double ctrlX1 = t * cPtX - (t - 1) * startPt.x;
        double ctrlY1 = t * cPtY - (t - 1) * startPt.y;
        double endX1 = t * t * endPt.x - 2 * t * (t - 1) * cPtX + (t - 1) * (t - 1) * startPt.x;
        double endY1 = t * t * endPt.y - 2 * t * (t - 1) * cPtY + (t - 1) * (t - 1) * startPt.y;

        Point start1 = new Point(startX1, startY1);
        Point end1 = new Point(endX1, endY1);

        double startX2 = t * t * endPt.x - 2 * t * (t - 1) * cPtX + (t - 1) * (t - 1) * startPt.x;
        double startY2 = t * t * endPt.y - 2 * t * (t - 1) * cPtY + (t - 1) * (t - 1) * startPt.y;
        double ctrlX2 = t * endPt.x - (t - 1) * cPtX;
        double ctrlY2 = t * endPt.y - (t - 1) * cPtY;
        double endX2 = endPt.x;
        double endY2 = endPt.y;

        Point start2 = new Point(startX2, startY2);
        Point end2 = new Point(endX2, endY2);

        // create copies of this flow using copyFlow() method instead of copy 
        // constructor to allow overriding classes to copy themselves.
        Flow flow1 = copyFlow();
        flow1.setStartPt(start1);
        flow1.setCtrlPt(ctrlX1, ctrlY1);
        flow1.setEndPt(end1);

        Flow flow2 = copyFlow();
        flow2.setStartPt(start2);
        flow2.setCtrlPt(ctrlX2, ctrlY2);
        flow2.setEndPt(end2);

        return new Flow[]{flow1, flow2};
    }

    /**
     * Returns the curve parameter where a circle with radius r around the end
     * point intersects the Bézier curve.
     *
     * @param r Radius of circle
     * @return Parameter t [0..1] where the circle intersects the flow.
     */
    public final double getIntersectionTWithCircleAroundEndPoint(double r) {
        if (r <= 0) {
            return 1;   // t = 1: end of curve
        }

        final double rSqr = r * r;

        // Test whether the entire curve is within the circle with radius r.
        // Compare r to the distance between start and end node.
        double baseLineDx = startPt.x - endPt.x;
        double baseLineDy = startPt.y - endPt.y;
        double baseLineLengthSqr = baseLineDx * baseLineDx + baseLineDy * baseLineDy;
        if (baseLineLengthSqr <= rSqr) {
            return 1; // t = 1: end of curve
        }

        // FIXME should use distance tolerance instead of hard-coded number of iterations
        double t = 0.5;
        double t_step = 0.25;
        for (int i = 0; i < 20; i++) {
            Point pt = pointOnCurve(t);
            final double dx = endPt.x - pt.x;
            final double dy = endPt.y - pt.y;
            final double dSqr = dx * dx + dy * dy;
            if (dSqr < rSqr) {
                t -= t_step;
            } else {
                t += t_step;
            }
            t_step /= 2;
        }

        return t;
    }

    /**
     * Returns the curve parameter where a circle with radius r around the start
     * point intersects the Bézier curve.
     *
     * @param r Radius of circle
     * @return Parameter t [0..1] where the circle intersects the flow.
     */
    public double getIntersectionTWithCircleAroundStartPoint(double r) {
        if (r <= 0) {
            return 0;   // t = 0: start of curve
        }

        final double rSqr = r * r;

        // Test whether the entire curve is within the circle with radius r.
        // Compare r to the distance between start and end node.
        double baseLineDx = startPt.x - endPt.x;
        double baseLineDy = startPt.y - endPt.y;
        double baseLineLengthSqr = baseLineDx * baseLineDx + baseLineDy * baseLineDy;
        if (baseLineLengthSqr <= rSqr) {
            return 0; // t = 1: start of curve
        }

        // FIXME should use distance tolerance instead of hard-coded number of iterations
        double t = 0.5;
        double t_step = 0.25;
        for (int i = 0; i < 20; i++) {
            Point pt = pointOnCurve(t);
            final double dx = startPt.x - pt.x;
            final double dy = startPt.y - pt.y;
            final double dSqr = dx * dx + dy * dy;
            if (dSqr < rSqr) {
                t += t_step;
            } else {
                t -= t_step;
            }
            t_step /= 2;
        }

        return t;
    }

    /**
     * Computes the clipping radius around the start or end node. The circle
     * intersects the passed lineString where the start or end clip area
     * intersects the lineString.
     *
     * @param lineString the geometry of this flow converted to straight line
     * segments.
     * @param clipWithStartArea If true, clip with start clip area, otherwise
     * clip with the end clip area.
     * @return The parameter t in [0, 1]
     */
    public double maskClippingRadius(LineString lineString, boolean clipWithStartArea) {
        Geometry clipArea = clipWithStartArea ? getStartClipArea() : getEndClipArea();
        Geometry clippedFlowLineGeometry = lineString.difference(clipArea);
        Point flowPoint = clipWithStartArea ? startPt : endPt;
        double d = 0;
        Iterator geometryIterator = new GeometryCollectionIterator(clippedFlowLineGeometry);
        while (geometryIterator.hasNext()) {
            Geometry geometry = (Geometry) geometryIterator.next();
            if (geometry instanceof LineString) {
                LineString l = (LineString) geometry;
                if (l.getNumPoints() >= 2) {
                    com.vividsolutions.jts.geom.Point linePoint
                            = clipWithStartArea ? l.getStartPoint() : l.getEndPoint();
                    double dx = flowPoint.x - linePoint.getX();
                    double dy = flowPoint.y - linePoint.getY();
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist > d) {
                        d = dist;
                    }
                }
            }
        }
        return d;
    }

    /**
     * Computes the shortest distance between a point and any point on this
     * quadratic Bézier curve. <STRONG>Attention: xy parameter is
     * changed.</STRONG>
     *
     * @param xy Point x and y on input; the closest point on the curve on
     * output.
     * @param tol Tolerance to test whether points are collinear.
     * @return The distance.
     */
    public double distance(double[] xy, double tol) {
        return GeometryUtils.getDistanceToQuadraticBezierCurve(startPt.x, startPt.y,
                cPtX, cPtY, endPt.x, endPt.y, tol, xy);
    }

    /**
     * Computes the square of the shortest distance between a point and any
     * point on this quadratic Bézier curve.
     *
     * @param x point x
     * @param y point y
     * @param tol Tolerance to test whether points are collinear.
     * @return the distance
     */
    public double distanceSquare(double x, double y, double tol) {
        return GeometryUtils.getDistanceToQuadraticBezierCurveSq(startPt.x, startPt.y,
                cPtX, cPtY, endPt.x, endPt.y, tol, x, y);
    }

    private double fp(double t, double x, double y) {
        // return (f(t + h, x, y) - f(t - h, x, y)) / (2 * h);

        // with square of distance
        // http://www.wolframalpha.com
        // derivative (i-(a*(1-x)^2+b*2*x(1-x)+c*x^2))^2 + (j-(d*(1-x)^2+e*2*x(1-x)+f*x^2))^2
        // return 2 * (2 * x1 * (1 - t) - 2 * x2 * (1 - t) + 2 * x2 * t - 2 * x3 * t) * (-x1 * (1 - t) * (1 * t) - 2 * x2 * t * (1 - t) - x3 * t * t + x)
        // + 2 * (2 * y1 * (1 - t) - 2 * y2 * (1 - t) + 2 * y2 * t - 2 * y3 * t) * (-y1 * (1 - t) * (1 - t) - 2 * y2 * t * (1 - t) - y3 * t * t + y);
        // with distance
        double x1 = startPt.x;
        double y1 = startPt.y;
        double x2 = cPtX;
        double y2 = cPtY;
        double x3 = endPt.x;
        double y3 = endPt.y;

        // D[sqrt ((x - (x1*(1 - t)^2 + x2*2*t (1 - t) + x3*t^2))^2 + (y - (y1*(1 - t)^2 + y2*2*t (1 - t) + y3*t^2))^2)), t]
        // then simplify
        /*
        (4 ((-1 + t) x1 + x2 - 2 t x2 + t x3) (-x + (-1 + t)^2 x1 + t (2 x2 - 2 t x2 + t x3)) + 
   4 ((-1 + t) y1 + y2 - 2 t y2 + t y3) (-y + (-1 + t)^2 y1 + 
      t (2 y2 - 2 t y2 + t y3)))/(2 \[Sqrt]((x - (-1 + t)^2 x1 + 
        t (2 (-1 + t) x2 - t x3))^2 + (y - (-1 + t)^2 y1 + 
        t (2 (-1 + t) y2 - t y3))^2))
         */
        // FIXME reduce number of operation
        double kx = (x - (-1 + t) * (-1 + t) * x1 + t * (2 * (-1 + t) * x2 - t * x3));
        double ky = (y - (-1 + t) * (-1 + t) * y1 + t * (2 * (-1 + t) * 2 - t * y3));

        return (4 * ((-1 + t) * x1 + x2 - 2 * t * x2 + t * x3) * (-x + (-1 + t) * (-1 + t) * x1
                + t * (2 * x2 - 2 * t * x2 + t * x3))
                + 4 * ((-1 + t) * y1 + y2 - 2 * t * y2 + t * y3) * (-y + (-1 + t) * (-1 + t) * y1
                + t * (2 * y2 - 2 * t * y2 + t * y3)))
                / (2 * Math.sqrt((kx * kx + ky * ky)));
    }

    private double fpp(double t, double x, double y) {
        // return (f(t + h, x, y) - 2 * f(t, x, y) + f(t - h, x, y)) / (h * h);

        // with square of distance
//        double kx = (2 * x1 * (1 - t) - 2 * x2 * (1 - t) + 2 * x2 * t - 2 * x3 * t);
//        double ky = (2 * y1 * (1 - t) - 2 * y2 * (1 - t) + 2 * y2 * t - 2 * y3 * t);
//        return 2 * (-2 * x1 + 4 * x2 - 2 * x3) * (-x1 * (1 - t) * (1 - t) - 2 * x2 * t * (1 - t) - x3 * t * t + x)
//                + 2 * kx * kx
//                + 2 * (-2 * y1 + 4 * y2 - 2 * y3) * (-y1 * (1 - t) * (1 - t) - 2 * y2 * t * (1 - t) - y3 * t * t + y)
//                + 2 * ky * ky;
        // with distance
        // second derivative with simplify
        /*(8 ((x + (-1 + t) (x1 - t x1 + 2 t x2) - 
        t^2 x3)^2 + (y + (-1 + t) (y1 - t y1 + 2 t y2) - 
        t^2 y3)^2) (2 ((-1 + t) x1 + x2 - 2 t x2 + t x3)^2 + (x1 - 
         2 x2 + x3) (-x + (-1 + t)^2 x1 + t (2 x2 - 2 t x2 + t x3)) + 
      2 ((-1 + t) y1 + y2 - 2 t y2 + t y3)^2 + (y1 - 2 y2 + 
         y3) (-y + (-1 + t)^2 y1 + 
         t (2 y2 - 2 t y2 + t y3))) - (4 ((-1 + t) x1 + x2 - 2 t x2 + 
        t x3) (-x + (-1 + t)^2 x1 + t (2 x2 - 2 t x2 + t x3)) + 
     4 ((-1 + t) y1 + y2 - 2 t y2 + t y3) (-y + (-1 + t)^2 y1 + 
        t (2 y2 - 2 t y2 + t y3)))^2)/(4 ((x + (-1 + t) (x1 - t x1 + 
          2 t x2) - t^2 x3)^2 + (y + (-1 + t) (y1 - t y1 + 2 t y2) - 
       t^2 y3)^2)^(3/2))
         */
        double x1 = startPt.x;
        double y1 = startPt.y;
        double x2 = cPtX;
        double y2 = cPtY;
        double x3 = endPt.x;
        double y3 = endPt.y;

        // FIXME reduce number of operation
        double kx1 = (x + (-1 + t) * (x1 - t * x1 + 2 * t * x2) - t * t * x3);
        double ky1 = (y + (-1 + t) * (y1 - t * y1 + 2 * t * y2) - t * t * y3);
        double kx2 = ((-1 + t) * x1 + x2 - 2 * t * x2 + t * x3);
        double ky2 = ((-1 + t) * y1 + y2 - 2 * t * y2 + t * y3);
        double kx3 = (x + (-1 + t) * (x1 - t * x1 + 2 * t * x2) - t * t * x3);
        double ky3 = (y + (-1 + t) * (y1 - t * y1 + 2 * t * y2) - t * t * y3);
        double k = (4 * ((-1 + t) * x1 + x2 - 2 * t * x2 + t * x3) * (-x + (-1 + t) * (-1 + t) * x1 + t * (2 * x2 - 2 * t * x2 + t * x3))
                + 4 * ((-1 + t) * y1 + y2 - 2 * t * y2 + t * y3) * (-y + (-1 + t) * (-1 + t) * y1
                + t * (2 * y2 - 2 * t * y2 + t * y3)));

        return (8 * (kx1 * kx1 + ky1 * ky1) * (2 * kx2 * kx2 + (x1 - 2 * x2 + x3) * (-x + (-1 + t) * (-1 + t) * x1 + t * (2 * x2 - 2 * t * x2 + t * x3))
                + 2 * ky2 * ky2 + (y1 - 2 * y2 + y3) * (-y + (-1 + t) * (-1 + t) * y1
                + t * (2 * y2 - 2 * t * y2 + t * y3))) - k * k) / (4 * Math.pow(kx3 * kx3 + ky3 * ky3, 3. / 2)); // FIXME replace pow(x, 1.5) with x * sqrt(x)
    }

    private double f(double t, double x, double y) {
        Point point = pointOnCurve(t);
        return point.distance(x, y);
    }

// FIXME   
//    public static void main(String[] args) {
//        double h = 0.0000001;
//
//        //Flow flow = new Flow(new Point(0,0), 0.5, 2, new Point(1, 0), 1);
//        Flow flow = new Flow(new Point(146.7, -0.04), 145.524, 6.855, new Point(151.97, -1.734), 1);
//
//        Flow flow2 = new Flow(new Point(144, 0), 144, 12, new Point(154, 0), 1);
//
//        for (int i = 0; i <= 20; i++) {
//            double t = i / 20d;
//            Point point = flow2.pointOnCurve(t);
//            double x = point.x;
//            double y = point.y;
//            double fp = flow.fp(t, x, y);
//            System.out.println("\nexact first derivative  \t" + fp);
//            fp = (flow.f(t + h, x, y) - flow.f(t - h, x, y)) / (2 * h);
//            System.out.println("approx first derivative \t" + fp);
//
//            double fpp = flow.fpp(t, x, y);
//            System.out.println("exact second derivative \t" + fpp);
//            fpp = (flow.f(t + h, x, y) - 2 * flow.f(t, x, y) + flow.f(t - h, x, y)) / (h * h);
//            System.out.println("approx second derivative \t" + fpp);
//
//            System.out.println("closest t               \t" + flow.closestTNewtonRaphson(t, x, y));
//
//            Point point1 = flow.closestPointOnCurve(t, new Point(x, y));
//            Point point2 = flow.closestPointOnCurve(new Point(x, y));
//            System.out.println("distance exact - Newton \t" + point1.distance(point2));
//        }
//
//    }
    private double closestTNewtonRaphson(double t, double x, double y) {
        final int MAX_ITER = 5;
        final double EPS = 0.0001;

        double dT = Double.MAX_VALUE;
        int iterationCounter = 0;

        // Newton-Raphson with first and second derivative to find minimum
        do {
            double fpp = fpp(t, x, y);
            if (fpp == 0d) {
                // found stationary point, which is the minimum
                return (t < 0d || t > 1d) ? -1d : t;
            }

            double fp = fp(t, x, y);
            double dT_ = fp / fpp;
            if (Math.abs(dT_) > Math.abs(dT)) {
                return -1d;
            }
            dT = dT_;
            t -= dT;
            if (++iterationCounter == MAX_ITER) {
                return -1d;
            }
        } while (Math.abs(dT) > EPS);

        if (t < 0d || t > 1d) {
            return -1d;
        }

        return t;
    }

    /**
     * Returns the point on this curve that is closest to a passed point.
     * Returns an approximate solution using Newton-Raphson root finding if
     * possible.
     *
     * @param t an estimate of the t parameter of the location of the closest
     * point on the curve.
     * @param pt search shortest distance to this point
     * @return the closest point on this curve
     */
    private Point closestPointOnCurve(double t, Point pt) {
        t = closestTNewtonRaphson(t, pt.x, pt.y);
        if (t == -1d) {
            return closestPointOnCurve(pt);
        }
        return pointOnCurve(t);
    }

    /**
     * FIXME duplicate of distance method
     *
     * @param pt
     * @return
     */
    private Point closestPointOnCurve(Point pt) {
        double[] xy = new double[]{pt.x, pt.y};
        GeometryUtils.getDistanceToQuadraticBezierCurveSq(startPt.x, startPt.y,
                cPtX, cPtY, endPt.x, endPt.y, 0.001, xy);
        return new Point(xy[0], xy[1]);
    }

    /**
     * Test whether x/y is beyond the start butt cap line.
     *
     * @param x point x
     * @param y point y
     * @return true if beyond start butt cap, false otherwise
     */
    private boolean beyondStartButtCap(double x, double y) {
        // test whether x/y is to the left of the start butt cap line
        // compute unnormalized normal nx/ny at start (t = 0)
        double dx = cPtX - startPt.x;
        double dy = cPtY - startPt.y;
        double nx = -dy;
        double ny = dx;
        // startPt and startPt + nx/ny define a line along the butt cap
        // test whether the point is on the left of this line (when viewing from start point along normal)
        // this is a simplified version of:
        // leftOfButtCapLine = GeometryUtils.isOnLeftSide(x, y, startX, startY, startX + nx, startY + ny);
        return nx * (y - startPt.y) - ny * (x - startPt.x) > 0d;
    }

    /**
     * Test whether x/y is beyond the end butt cap line.
     *
     * @param x point x
     * @param y point y
     * @return true if beyond end butt cap, false otherwise
     */
    private boolean beyondEndButtCap(double x, double y) {
        // compute unnormalized normal nx/ny at end (t = 0)
        double dx = cPtX - endPt.x;
        double dy = cPtY - endPt.y;
        double nx = -dy;
        double ny = dx;
        // endPt and endPt + nx/ny define a line along the end butt cap
        // test whether the point is on the left of this line (when viewing from end point along normal)
        return nx * (y - endPt.y) - ny * (x - endPt.x) > 0d;
    }

    /**
     * Tests whether a point is beyond the start or end butt cap lines. These
     * are the lines that terminate the flow bands.
     *
     * @param x point x
     * @param y point y
     * @return true if beyond butt caps, false otherwise
     */
    private boolean beyondButtCaps(double x, double y) {
        return beyondStartButtCap(x, y) || beyondEndButtCap(x, y);
    }

    /**
     * Test whether a perpendicular cross section of this flow at parameter t
     * (between 0 and 1) overlaps with any other flow or their arrowheads. Takes
     * the width of flows and arrowheads into account.
     *
     * FIXME move to Model?
     *
     * @param t location of the cross section
     * @param model model with all flows
     * @return true if there is an overlap with another flow, false otherwise.
     */
    public boolean isOverlappingAnyFlowAtPoint(double t, Model model) {
        Point p = pointOnCurve(t);
        double[] n = getNormal(t);
        double tangentX = n[1];
        double tangentY = -n[0];
        double refScale = model.getReferenceMapScale();
        double halfWidth = model.getFlowWidthPx(this) / refScale / 2;
        double p1x = p.x + n[0] * halfWidth;
        double p1y = p.y + n[1] * halfWidth;
        double p2x = p.x - n[0] * halfWidth;
        double p2y = p.y - n[1] * halfWidth;
        double tol = 0.2 / refScale;

        Iterator<Flow> iterator = model.flowIterator();
        while (iterator.hasNext()) {

            Flow[] flows;
            {
                Flow flow = iterator.next();
                if (flow.id == id) {
                    continue;
                }

                // FIXME test with bounding boxes first
                // ...
                if (flow instanceof FlowPair) {
                    FlowPair flowPair = (FlowPair) flow;
                    flows = flowPair.createOffsetFlows(model, FlowOffsettingQuality.HIGH);
                } else {
                    flows = new Flow[]{flow};
                }
            }

            for (Flow flow : flows) {
                Flow clippedFlow = model.clipFlow(flow, true, true, true);

                double hw = model.getFlowWidthPx(clippedFlow) / refScale / 2d;
                if (clippedFlow.isIntersectingLineSegment(
                        p1x + hw * tangentX, p1y + hw * tangentY,
                        p2x + hw * tangentX, p2y + hw * tangentY)) {
                    return true;
                }
                if (clippedFlow.isIntersectingLineSegment(
                        p1x - hw * tangentX, p1y - hw * tangentY,
                        p2x - hw * tangentX, p2y - hw * tangentY)) {
                    return true;
                }
                if (clippedFlow.isIntersectingLineSegment(
                        p2x + hw * tangentX, p2y + hw * tangentY,
                        p2x - hw * tangentX, p2y - hw * tangentY)) {
                    return true;
                }
                if (clippedFlow.isIntersectingLineSegment(
                        p1x - hw * tangentX, p1y - hw * tangentY,
                        p1x + hw * tangentX, p1y + hw * tangentY)) {
                    return true;
                }
                // test whether the start point or the end point of the cross section
                // overlap the flow
                double hwSqr = hw * hw;
                // FIXME test for beyondButtCaps ?
                if (clippedFlow.distanceSquare(p1x, p1y, tol) < hwSqr) {
                    return true;
                }
                // FIXME test for beyondButtCaps ?
                if (clippedFlow.distanceSquare(p2x, p2y, tol) < hwSqr) {
                    return true;
                }
                // test with arrowhead
                if (flow.getArrow(model).lineSegmentOverlaps(p1x, p1y, p2x, p2y)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Compute an index between 0 and 1 that indicates how close this flow is to
     * another flow. 0 indicates the flows are not touching. 1 indicates this
     * flow touches the other flow along the entire length of this flow.
     *
     * FIXME override in FlowPair
     *
     * @param flow the other flow
     * @param minDist if a point on this flow is closer than minDist to the
     * other flow, the point is considered to touch the other flow.
     * @param nbrPointsToTest test this many locations along the curve (used to
     * compute curve parameter t)
     * @return a value between 0 and 1.
     */
    public double touchPercentage(Flow flow, double minDist, int nbrPointsToTest) {
        int closePoints = 0;
        double minDistSqr = minDist * minDist;
        for (int i = 0; i < nbrPointsToTest; i++) {
            double t = i / (nbrPointsToTest - 1d);
            Point pt = pointOnCurve(t);
            // FIXME double tol = 0.2 / model.getReferenceMapScale();
            double dSqr = flow.distanceSquare(pt.x, pt.y, 0.001); // FIXME
            if (dSqr < minDistSqr) {
                ++closePoints;
            }
        }
        return ((double) closePoints) / nbrPointsToTest;
    }

    /**
     * The length of the line connecting the start point and the control point.
     *
     * @return the length
     */
    public double getDistanceBetweenStartPointAndControlPoint() {
        double dx = cPtX - startPt.x;
        double dy = cPtY - startPt.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * The length of the line connecting the end point and the control point.
     *
     * @return the length
     */
    public double getDistanceBetweenEndPointAndControlPoint() {
        double dx = cPtX - endPt.x;
        double dy = cPtY - endPt.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * The 2D vector pointing from the start point to the control point with
     * length 1.
     *
     * @return the vector. Components are NaN if the start point and the end
     * point coincide.
     */
    public double[] getDirectionVectorFromStartPointToControlPoint() {
        double dx = cPtX - startPt.x;
        double dy = cPtY - startPt.y;
        double d = Math.sqrt(dx * dx + dy * dy);
        return new double[]{dx / d, dy / d};
    }

    /**
     * The 2D vector pointing from the end point to the control point with
     * length 1.
     *
     * @return the vector. Components are NaN if the start point and the end
     * point coincide.
     */
    public double[] getDirectionVectorFromEndPointToControlPoint() {
        double dx = cPtX - endPt.x;
        double dy = cPtY - endPt.y;
        double d = Math.sqrt(dx * dx + dy * dy);
        return new double[]{dx / d, dy / d};
    }

    /**
     * Orientation of the line between the start point and the control point.
     *
     * @return Angle in radians relative to horizontal x axis in
     * counter-clockwise direction.
     */
    public double startToCtrlAngle() {
        double dx = cPtX - startPt.x;
        double dy = cPtY - startPt.y;
        return FastMath.atan2(dy, dx);
    }

    /**
     * Orientation of the line between the end point and the control point.
     *
     * @return Angle in radians relative to horizontal x axis in
     * counter-clockwise direction.
     */
    public double endToCtrlAngle() {
        double dx = cPtX - endPt.x;
        double dy = cPtY - endPt.y;
        return FastMath.atan2(dy, dx);
    }

    /**
     * The clip area for the flow start in WKT format.
     *
     * @return the startClipAreaWKT
     */
    public String getStartClipAreaWKT() {
        return startClipAreaWKT;
    }

    /**
     * The clip area for the flow end in WKT format.
     *
     * @return the endClipAreaWKT
     */
    public String getEndClipAreaWKT() {
        return endClipAreaWKT;
    }

    /**
     * If this and the passed flow share a common start or end node, the shared
     * node is returned. Otherwise null is returned.
     *
     * @param flow flow to test.
     * @return the shared node or null.
     */
    public Point getSharedNode(Flow flow) {
        if (startPt == flow.startPt || startPt == flow.endPt) {
            return startPt;
        }
        if (endPt == flow.startPt || endPt == flow.endPt) {
            return endPt;
        }
        return null;
    }

    /**
     * Converts the Bézier curve to straight-line segments with irregular
     * length. Uses the de Casteljau algorithm of the Java2D library.
     *
     * @param deCasteljauTol the de Casteljau tolerance, which is the maximum
     * distance between the Bézier curve and the approximation by straight line
     * segments.
     * @return
     */
    public ArrayList<Point> irregularIntervals(double deCasteljauTol) {
        assert (deCasteljauTol > 0);

        ArrayList<Point> irregularPoints = new ArrayList<>();
        GeneralPath path = new GeneralPath();
        path.moveTo(startPt.x, startPt.y);
        path.quadTo(cPtX, cPtY, endPt.x, endPt.y);
        PathIterator iter = path.getPathIterator(null, deCasteljauTol);
        double[] coords = new double[6];
        while (!iter.isDone()) {
            iter.currentSegment(coords);
            irregularPoints.add(new Point(coords[0], coords[1]));
            iter.next();
        }
        return irregularPoints;
    }

    /**
     * Returns a list of points at regular intervals on the flow curve. The
     * first and last points are slightly moved along the flow line away from
     * the start and end points. There are always at least two points returned.
     *
     * @param intervalLength target interval length. The actual length will vary
     * to create an entire number of intervals.
     * @return list of points, including copies of the start and end points of
     * this flow
     */
    public ArrayList<Point> regularIntervals(double intervalLength) {
        assert (intervalLength > 0);

        // move first and last points away from flow start and end nodes by 5 percent of the interval length 
        final double OFFSET = 0.05;

        ArrayList<Point> intervalPoints = new ArrayList<>();

        // compute size of lookup table
        // The length of the curve is always shorter or equal to the distance 
        // between the start point and the control point plus the distance 
        // between the control point and the end point. Use this longer distance
        // as an approximation for the real curve length to compute the number
        // of points in the lookup table.
        double d1 = getDistanceBetweenStartPointAndControlPoint();
        double d2 = getDistanceBetweenEndPointAndControlPoint();
        int lutSize = (int) ((d1 + d2) / intervalLength) + 1;
        lutSize = Math.max(2, lutSize);
        double[] lut = new double[lutSize];

        // fill lookup table with length values for regularly increasing t value
        double x0 = startPt.x;
        double y0 = startPt.y;
        double lineLength = 0;
        lut[0] = 0;
        for (int i = 1; i < lutSize; i++) {
            // t parameter
            double t = (double) i / (lutSize - 1);

            // compute position on curve for t
            double t_1 = t - 1;
            double a = t * t;
            double b = 2 * t * t_1;
            double c = t_1 * t_1;
            double x1 = a * endPt.x - b * cPtX + c * startPt.x;
            double y1 = a * endPt.y - b * cPtY + c * startPt.y;

            // distance to previous point. Use Eucledian distance.
            double dx = x0 - x1;
            double dy = y0 - y1;
            lineLength += Math.sqrt(dx * dx + dy * dy);
            lut[i] = lineLength;

            x0 = x1;
            y0 = y1;
        }

        // make sure there are at least two points
        if (lineLength <= intervalLength) {
            // add start point
            intervalPoints.add(pointOnCurve(tForLength(lut, lineLength * OFFSET)));
            // add end point
            intervalPoints.add(pointOnCurve(tForLength(lut, lineLength * (1. - OFFSET))));
            return intervalPoints;
        }

        // number and length of intervals
        int nbrIntervals = (int) Math.round(lineLength / intervalLength);
        nbrIntervals = Math.max(nbrIntervals, 2);
        intervalLength = lineLength / nbrIntervals;

        // add start point
        intervalPoints.add(pointOnCurve(tForLength(lut, intervalLength * OFFSET)));

        // add intermediate points
        int lutID = 1;
        for (int i = 1; i < nbrIntervals; i++) {
            double distance = i * intervalLength;
            double t = 1d;

            // find t parameter in lookup table for given distance
            for (; lutID < lutSize; lutID++) {
                if (lut[lutID] > distance) {
                    double t1 = (lutID - 1d) / (lutSize - 1);
                    double dT = 1d / (lutSize - 1);
                    double l1 = lut[lutID - 1];
                    double l2 = lut[lutID];
                    t = dT * (distance - l1) / (l2 - l1) + t1;
                    break;
                }
            }

            t = Math.max(Math.min(t, 1d), 0d);
            intervalPoints.add(pointOnCurve(t));

        }

        // add end point
        intervalPoints.add(pointOnCurve(tForLength(lut, lineLength - intervalLength * OFFSET)));

        return intervalPoints;
    }

    /**
     * Read t parameter from lookup table for given distance from start of flow.
     *
     * @param lut lookup table
     * @param distance distance from start of flow
     * @return t parameter
     */
    private double tForLength(double[] lut, double distance) {
        for (int lutID = 1; lutID < lut.length; lutID++) {
            if (lut[lutID] > distance) {
                double t1 = (lutID - 1d) / (lut.length - 1);
                double dT = 1d / (lut.length - 1);
                double l1 = lut[lutID - 1];
                double l2 = lut[lutID];
                double t = dT * (distance - l1) / (l2 - l1) + t1;
                return Math.max(Math.min(t, 1d), 0d);
            }
        }
        return 1d;
    }

    /**
     * Computes the geometry of an arrowhead and returns the geometry as a new
     * Arrow.
     *
     * @param model model
     * @param arrowTipClipRadius the tip of the arrow is placed at this distance
     * from the end of the flow
     * @return a new Arrow instance
     */
    protected Arrow getArrow(Model model, double arrowTipClipRadius) {
        return new Arrow(model, this, arrowTipClipRadius);
    }

    /**
     * Computes and returns the geometry of an arrowhead.
     *
     * @param model model
     * @return a new Arrow instance
     */
    public Arrow getArrow(Model model) {
        double arrowTipClipRadius = model.endClipRadius(this,
                /* clipArrrowhead */ false,
                /* lineString */ null,
                /* clipEndNode */ true,
                /* adjustLengthToReduceOverlaps */ true);
        return getArrow(model, arrowTipClipRadius);
    }

    /**
     * Cuts this flow with two circles both centered on the start point.
     *
     * @param r1 smaller circle radius
     * @param r2 greater circler radius
     * @return a new Flow
     */
    public Flow clipAroundStartNode(double r1, double r2) {
        Flow clippedFlow = this;

        // cut off the end piece
        double endT = clippedFlow.getIntersectionTWithCircleAroundStartPoint(r2);
        if (endT < 1) {
            clippedFlow = split(endT)[0];
        }

        // cut off the start piece
        double startT = clippedFlow.getIntersectionTWithCircleAroundStartPoint(r1);
        if (startT > 0) {
            clippedFlow = clippedFlow.split(startT)[1];
        }

        return clippedFlow;
    }

    /**
     * Cuts this flow with two circles both centered on the end point.
     *
     * @param r1 smaller circle radius
     * @param r2 greater circler radius
     * @return a new Flow
     */
    public Flow clipAroundEndNode(double r1, double r2) {
        Flow clippedFlow = this;

        // cut off the end piece
        double endT = clippedFlow.getIntersectionTWithCircleAroundEndPoint(r2);
        if (endT < 1) {
            clippedFlow = split(endT)[1];
        }

        // cut off the start piece
        double startT = clippedFlow.getIntersectionTWithCircleAroundEndPoint(r1);
        if (startT > 0) {
            clippedFlow = clippedFlow.split(startT)[0];
        }

        return clippedFlow;
    }

    /**
     * Search through last n elements of array and test whether any of the
     * values is true, indicating an overlap.
     *
     * @param candidates array to test
     * @param n number of array elements to test
     * @return true if at least one of the last n elements of candidates is
     * true.
     */
    private boolean lastPositionsOverlap(ArrayList<Boolean> candidates, int n) {
        if (candidates.size() < n) {
            return true;
        }
        for (int i = 1; i <= n; i++) {
            boolean foundOverlap = candidates.get(candidates.size() - i);
            if (foundOverlap) {
                return true;
            }
        }
        // no overlap found in last n array elements
        return false;
    }

    /**
     * Adjust the end of this Flow to reduce overlaps of this Flow and its Arrow
     * with other lines and arrowheads.
     *
     * @param model the Model with all flows
     */
    public void adjustEndShorteningToAvoidOverlaps(Model model) {
        final int RAD_INC_PX = 1;

        final double referenceMapScale = model.getReferenceMapScale();
        final double radiusIncrement = RAD_INC_PX / referenceMapScale;
        double minFlowLength = model.getMinFlowLengthPx() / referenceMapScale;
        double thisWidthPx = model.getFlowWidthPx(this);
        double thisWidth = thisWidthPx / referenceMapScale;
        double endNodeRadiusPx = model.getNodeStrokeWidthPx() / 2 + model.getNodeRadiusPx(getEndPt());
        double endNodeRadius = endNodeRadiusPx / referenceMapScale;

        // Node radius for testing whether the flow end points at the end node.
        // Make sure the circle is as large as the width of the flow.
        double visualNodeRadius = Math.max(thisWidth / 2d, endNodeRadius);

        double endR = model.endClipRadius(this,
                /* clipArrowhead */ false,
                /* lineString */ null,
                /* clipEndNode */ true,
                /* adjustLengthToReduceOverlaps */ false);

        int nbrIterations = (int) (model.getMaxShorteningPx() / RAD_INC_PX);
        ArrayList<Boolean> candidates = new ArrayList<>(nbrIterations);
        for (int i = 0; i <= nbrIterations; i++) {
            endShorteningToAvoidOverlaps = i * radiusIncrement;
            double clipRadius = endR + endShorteningToAvoidOverlaps;

            // FIXME make sure clip radius is not too large
            // ...
            // make sure the arrow (or end of line) is pointing at the end node circle
            double t = getIntersectionTWithCircleAroundEndPoint(endR + endShorteningToAvoidOverlaps);
            if (isTangentDirectedTowardsEndNode(t, visualNodeRadius) == false) {
                endShorteningToAvoidOverlaps = 0; // unlike the start of flows, do not shorten end of flows
                break;
            }

            // make sure flow trunk (flow without arrowhead) is long enough
            if (isFlowTrunkLongerThan(minFlowLength, model) == false) {
                endShorteningToAvoidOverlaps = 0; // unlike the start of flows, do not shorten end of flows
                break;
            }

            // when arrowheads are drawn, make sure the arrowhead with the
            // current value of endShorteningToAvoidOverlaps does not overlap 
            // any other flow or arrowhead
            if (model.isDrawArrowheads()) {
                Arrow arrow = getArrow(model, clipRadius);
                if (model.arrowOverlapsAnyFlow(arrow) == false
                        && model.arrowOverlapsAnyArrow(arrow, this) == false) {
                    break;
                }
            } else {
                // when arrowheads are not drawn, test whether the current end
                // of the flow overlaps any other flow
                candidates.add(isOverlappingAnyFlowAtPoint(t, model));

                // if the n last tests did not find any overlap, we have a good value 
                // for endShorteningToAvoidOverlaps.
                int n = model.consecutivePixelsWithoutOverlapToShortenFlow;
                if (lastPositionsOverlap(candidates, n) == false) {
                    // no overlap found with last n tests. Return the first of the n values.
                    endShorteningToAvoidOverlaps = (candidates.size() - n) * radiusIncrement;
                    break;
                }
            }
        }
    }

    /**
     * Shortens the start of the flow to reduce overlaps of this flow with other
     * lines and arrowheads.
     *
     * @param model the Model with all Flows
     */
    public void adjustStartShorteningToAvoidOverlaps(Model model) {
        final double RAD_INC_PX = 1;

        final double referenceMapScale = model.getReferenceMapScale();
        final double radiusIncrement = RAD_INC_PX / referenceMapScale;
        double minFlowLength = model.getMinFlowLengthPx() / referenceMapScale;
        double thisWidthPx = model.getFlowWidthPx(this);
        double thisWidth = thisWidthPx / referenceMapScale;
        double startNodeRadiusPx = model.getNodeStrokeWidthPx() / 2 + model.getNodeRadiusPx(getStartPt());
        double startNodeRadius = startNodeRadiusPx / referenceMapScale;

        // Node radius for testing whether the flow start points at the start node.
        // Make sure the circle is as large as the width of the flow.
        double visualNodeRadius = Math.max(thisWidth / 2d, startNodeRadius);
        double startR = model.startClipRadius(this,
                /* forceClipNodes */ true,
                /* adjustLengthToReduceOverlaps */ false);

        int nbrIterations = (int) (model.getMaxShorteningPx() / RAD_INC_PX);
        ArrayList<Boolean> candidates = new ArrayList<>(nbrIterations);
        for (int i = 0; i <= nbrIterations; i++) {
            startShorteningToAvoidOverlaps = i * radiusIncrement;

            // make sure the tail of the flow is pointing towards the start node
            double t = getIntersectionTWithCircleAroundStartPoint(startR + startShorteningToAvoidOverlaps);
            if (isTangentDirectedTowardsStartNode(t, visualNodeRadius) == false) {
                startShorteningToAvoidOverlaps = Math.max(0, (i - 1) * radiusIncrement);
                break;
            }

            // make sure the flow trunk (flow without arrowhead) is long enough
            if (isFlowTrunkLongerThan(minFlowLength, model) == false) {
                startShorteningToAvoidOverlaps = Math.max(0, (i - 1) * radiusIncrement);
                break;
            }

            // test whether the current start of the flow overlaps any other flow
            candidates.add(isOverlappingAnyFlowAtPoint(t, model));

            // if the n last tests did not find any overlap, we have a good value 
            // for startShorteningToAvoidOverlaps.
            int n = model.consecutivePixelsWithoutOverlapToShortenFlow;
            if (lastPositionsOverlap(candidates, n) == false) {
                // no overlap found with last n tests. Return the first of the n values.
                startShorteningToAvoidOverlaps = (candidates.size() - n) * radiusIncrement;
                break;
            }
        }
    }

    /**
     * @return the approximateEndAreaClipRadius
     */
    public double getApproximateEndAreaClipRadius() {
        return approximateEndAreaClipRadius;
    }

    /**
     * @return the approximateStartAreaClipRadius
     */
    public double getApproximateStartAreaClipRadius() {
        return approximateStartAreaClipRadius;
    }

    /**
     * Compute intersections with another quadratic Bézier flow.
     *
     * Based on code by Kevin Lindsey,
     * http://www.kevlindev.com/geometry/2D/intersections/index.htm
     *
     * @param flow other flow
     * @return array with intersection Points or null if no intersection point
     * exists.
     */
    public Point[] intersections(Flow flow) {

        final double TOLERANCE = 1e-4;

        if (getBoundingBox().intersects(flow.getBoundingBox()) == false) {
            return null;
        }

        ArrayList<Point> result = new ArrayList<>(4);

        double c12x = startPt.x - 2d * cPtX + endPt.x;
        double c12y = startPt.y - 2d * cPtY + endPt.y;
        double c11x = 2d * (cPtX - startPt.x);
        double c11y = 2d * (cPtY - startPt.y);
        double c10x = startPt.x;
        double c10y = startPt.y;
        double c22x = flow.startPt.x - 2d * flow.cPtX + flow.endPt.x;
        double c22y = flow.startPt.y - 2d * flow.cPtY + flow.endPt.y;
        double c21x = 2d * (flow.cPtX - flow.startPt.x);
        double c21y = 2d * (flow.cPtY - flow.startPt.y);
        double c20x = flow.startPt.x;
        double c20y = flow.startPt.y;

        double a = c12x * c11y - c11x * c12y;
        double b = c22x * c11y - c11x * c22y;
        double c = c21x * c11y - c11x * c21y;
        double d = c11x * (c10y - c20y) + c11y * (-c10x + c20x);
        double e = c22x * c12y - c12x * c22y;
        double f = c21x * c12y - c12x * c21y;
        double g = c12x * (c10y - c20y) + c12y * (-c10x + c20x);
        Polynomial poly = new Polynomial(-e * e, -2 * e * f, a * b - f * f - 2 * e * g, a * c - 2 * f * g, a * d - g * g);
        double[] roots = poly.getRoots();
        for (int i = 0; i < roots.length; i++) {
            double s = roots[i];
            if (0 <= s && s <= 1) {
                double[] xRoots = new Polynomial(-c12x, -c11x, -c10x + c20x + s * c21x + s * s * c22x).getRoots();
                double[] yRoots = new Polynomial(-c12y, -c11y, -c10y + c20y + s * c21y + s * s * c22y).getRoots();
                if (xRoots.length > 0 && yRoots.length > 0) {
                    checkRoots:
                    for (int j = 0; j < xRoots.length; j++) {
                        double xRoot = xRoots[j];
                        if (0 <= xRoot && xRoot <= 1) {
                            for (int k = 0; k < yRoots.length; k++) {
                                if (Math.abs(xRoot - yRoots[k]) < TOLERANCE) {
                                    double x = c22x * s * s + c21x * s + c20x;
                                    double y = c22y * s * s + c21y * s + c20y;
                                    result.add(new Point(x, y));
                                    break checkRoots;
                                }
                            }
                        }
                    }
                }
            }
        }
        return result.toArray(new Point[result.size()]);
    }

    /**
     * Test whether a line segment intersects with this flow line.
     *
     * Based on code by Kevin Lindsey,
     * http://www.kevlindev.com/geometry/2D/intersections/index.htm
     * https://github.com/thelonious/js-intersections
     *
     * @param a1 start point of line segment
     * @param a2 end point of line segment
     * @return true if the line segment intersects this flow, false otherwise.
     */
    public boolean isIntersectingLineSegment(Point a1, Point a2) {
        return isIntersectingLineSegment(a1.x, a1.y, a2.x, a2.y);
    }

    /**
     * Test whether a line segment intersects with this flow line.
     *
     * Based on code by Kevin Lindsey,
     * http://www.kevlindev.com/geometry/2D/intersections/index.htm
     * https://github.com/thelonious/js-intersections
     *
     * @param x1 x start point of line segment
     * @param y1 y start point of line segment
     * @param x2 x end point of line segment
     * @param y2 y end point of line segment
     * @return true if the line segment intersects this flow, false otherwise.
     */
    public boolean isIntersectingLineSegment(double x1, double y1, double x2, double y2) {
        // used to determine if point is on line segment
        double minX = Math.min(x1, x2);
        double minY = Math.min(y1, y2);
        double maxX = Math.max(x1, x2);
        double maxY = Math.max(y1, y2);

        // coefficients of quadratic
        double c2x = startPt.x - 2d * cPtX + endPt.x;
        double c2y = startPt.y - 2d * cPtY + endPt.y;
        double c1x = 2d * (cPtX - startPt.x);
        double c1y = 2d * (cPtY - startPt.y);
        double c0x = startPt.x;
        double c0y = startPt.y;

        // Convert line to normal form: ax + by + c = 0
        // Find normal to line: negative inverse of original line's slope
        double nx = y1 - y2;
        double ny = x2 - x1;

        // Determine new c coefficient fr normal form of line
        double cl = x1 * y2 - x2 * y1;

        // Transform cubic coefficients to line's coordinate system and find roots
        // of cubic
        double[] roots = new Polynomial(nx * c2x + ny * c2y,
                nx * c1x + ny * c1y,
                nx * c0x + ny * c0y + cl).getRoots();

        // Any roots in closed interval [0,1] are intersections on Bezier, but
        // might not be on the line segment.
        // Find intersections and calculate point coordinates
        for (int i = 0; i < roots.length; i++) {
            double t = roots[i];
            if (0 <= t && t <= 1) {
                // We're within the Bezier curve
                // Find point on Bezier
                double p4x = startPt.x + (cPtX - startPt.x) * t;
                double p4y = startPt.y + (cPtY - startPt.y) * t;
                double p5x = cPtX + (endPt.x - cPtX) * t;
                double p5y = cPtY + (endPt.y - cPtY) * t;
                double p6x = p4x + (p5x - p4x) * t;
                double p6y = p4y + (p5y - p4y) * t;

                // See if point is on line segment
                // Had to make special cases for vertical and horizontal lines due
                // to slight errors in calculation of p6
                if (p6x >= minX && p6y >= minY && p6x <= maxX && p6y <= maxY) {
                    // p6 is intersection point
                    return true;
                } else if (x1 == x2) {
                    if (minY <= p6y && p6y <= maxY) {
                        // p6 is intersection point
                        return true;
                    }
                } else if (y1 == y2) {
                    if (minX <= p6x && p6x <= maxX) {
                        // p6 is intersection point
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Tests whether this flow overlaps an obstacle.
     *
     * @param obstacle obstacle to test against obstacles are considered
     * @param model model with all flows
     * @param minObstaclesDistPx minimum empty space between the flow and
     * obstacles (in pixels)
     * @return true if the flow overlaps a node
     */
    public boolean isOverlappingObstacle(Obstacle obstacle, Model model, int minObstaclesDistPx) {

        // ignore obstacles that are start or end nodes of the flow
        if (obstacle.node == getStartPt() || obstacle.node == getEndPt()) {
            return false;
        }

        // ignore arrowhead attached to this flow
        if (model.isDrawArrowheads() && obstacle.isArrowObstacleForFlow(this)) {
            return false;
        }

        // ignore arrowheads that are attached to this flow's start or end node
        if (obstacle.isArrowObstacle()) {
            if (obstacle.flow.getStartPt() == getStartPt()
                    || obstacle.flow.getStartPt() == getEndPt()
                    || obstacle.flow.getEndPt() == getStartPt()
                    || obstacle.flow.getEndPt() == getEndPt()) {
                return false;
            }
        }

        return cachedClippedCurveIncludingArrowIntersectsObstacle(obstacle,
                model, minObstaclesDistPx);
    }

    /**
     * Tests whether this Flow overlaps with an arrow. The arrow is treated as a
     * triangle consisting of the tip point and the two corner points. The flow
     * width is taken into account.
     *
     * @param arrow arrow to test with
     * @param model model
     * @return true if there is an overlap, false otherwise.
     */
    public boolean isOverlappingArrow(Arrow arrow, Model model) {
        // FIXME add test with bounding boxes?

        Flow clippedFlow = model.clipFlow(this,
                /* clipArrowhead */ true,
                /* forceClipNodes */ true,
                /* adjustLengthToReduceOverlaps */ true);

        // test whether the flow intersects the triangle formed by the arrow corners
        if (clippedFlow.isIntersectingLineSegment(arrow.getTipPt(), arrow.getCorner1Pt())
                || clippedFlow.isIntersectingLineSegment(arrow.getTipPt(), arrow.getCorner2Pt())
                || clippedFlow.isIntersectingLineSegment(arrow.getCorner1Pt(), arrow.getCorner2Pt())) {
            return true;
        }

        // the center line of the flow does not intersect the arrow triangle,
        // but it may still overlay parts of the arrow. So test whether any 
        // triangle vertices overlap the flow band.
        double flowWidthPx = model.getFlowWidthPx(this);
        double flowWidth = flowWidthPx / model.getReferenceMapScale();
        double minDist = flowWidth / 2d;
        double minDistSqr = minDist * minDist;
        double colinearTol = 0.2 /* px */ / model.getReferenceMapScale();

        double x = arrow.getTipPt().x;
        double y = arrow.getTipPt().y;
        if (clippedFlow.beyondStartButtCap(x, y) == false
                && clippedFlow.distanceSquare(x, y, colinearTol) < minDistSqr) {
            return true;
        }

        x = arrow.getCorner1Pt().x;
        y = arrow.getCorner1Pt().y;
        if (clippedFlow.beyondStartButtCap(x, y) == false
                && clippedFlow.distanceSquare(x, y, colinearTol) < minDistSqr) {
            return true;
        }

        x = arrow.getCorner2Pt().x;
        y = arrow.getCorner2Pt().y;
        return (clippedFlow.beyondStartButtCap(x, y) == false
                && clippedFlow.distanceSquare(x, y, colinearTol) < minDistSqr);
    }

    /**
     * Tests whether a passed Arrow overlaps with the Arrow of this Flow.
     *
     * The two arrows are treated as triangles consisting of the tip point and
     * the two corner points.
     *
     * @param arrow arrow to test with
     * @param model the Model with all flows
     * @return true if there is an overlap, false otherwise.
     */
    public boolean isArrowOverlappingArrow(Arrow arrow, Model model) {
        Point tipPt1 = arrow.getTipPt();
        Point corner1Pt1 = arrow.getCorner1Pt();
        Point corner2Pt1 = arrow.getCorner2Pt();

        Arrow thisArrow = getArrow(model);
        Point tipPt2 = thisArrow.getTipPt();
        Point corner1Pt2 = thisArrow.getCorner1Pt();
        Point corner2Pt2 = thisArrow.getCorner2Pt();

        return GeometryUtils.trianglesOverlap(tipPt1.x, tipPt1.y,
                corner1Pt1.x, corner1Pt1.y,
                corner2Pt1.x, corner2Pt1.y,
                tipPt2.x, tipPt2.y,
                corner1Pt2.x, corner1Pt2.y,
                corner2Pt2.x, corner2Pt2.y);
    }

    /**
     * @return the endShorteningToAvoidOverlaps
     */
    public double getEndShorteningToAvoidOverlaps() {
        return endShorteningToAvoidOverlaps;
    }

    /**
     * @return the startShorteningToAvoidOverlaps
     */
    public double getStartShorteningToAvoidOverlaps() {
        return startShorteningToAvoidOverlaps;
    }

    public void resetShortenings() {
        endShorteningToAvoidOverlaps = 0;
        startShorteningToAvoidOverlaps = 0;
    }

    /**
     * Copy control point location and shortening values from the passed Flow to
     * this Flow.
     *
     * @param flow flow to copy from
     */
    public void updateControlPointAndShortening(Flow flow) {
        setCtrlPt(flow.cPtX(), flow.cPtY());
        endShorteningToAvoidOverlaps = flow.endShorteningToAvoidOverlaps;
        startShorteningToAvoidOverlaps = flow.startShorteningToAvoidOverlaps;
    }

    /**
     * Test whether the passed point hits this flow line or the arrowhead.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param tolerance the point x/y can miss the flow line by this much and
     * will still be considered on the line.
     * @param model model with all flows
     * @return true if the flow line is hit, false otherwise.
     */
    public boolean hit(double x, double y, double tolerance, Model model) {
        // flow width
        double flowWidth = model.getFlowWidthPx(this) / model.getReferenceMapScale();

        // maximum distance between center line of flow and point x/y
        double maxDist = tolerance + flowWidth / 2;

        // Add padding to the bounding box in the amount of tolerance
        Rectangle2D flowBB = getBoundingBox();
        flowBB.add(flowBB.getMinX() - maxDist, flowBB.getMinY() - maxDist);
        flowBB.add(flowBB.getMaxX() + maxDist, flowBB.getMaxY() + maxDist);
        if (flowBB.contains(x, y) == false) {
            return false;
        }

        // test with flow line without arrowhead
        Flow clipppedFlow = model.clipFlow(this, true, true, true);

        // test whether x/y is to the left of the start butt cap line
        // compute unnormalized normal nx/ny at start (t = 0)
        double startX = clipppedFlow.getStartPt().x;
        double startY = clipppedFlow.getStartPt().y;
        double dx = clipppedFlow.cPtX - startX;
        double dy = clipppedFlow.cPtY - startY;
        double nx = -dy;
        double ny = dx;
        // startPt and startPt + nx/ny define a line along the butt cap
        // test whether the point is on the left of this line (when viewing from start point along normal)
        // this is a simplified version of:
        // leftOfButtCapLine = GeometryUtils.isOnLeftSide(x, y, startX, startY, startX + nx, startY + ny);
        boolean leftOfStartButtCapLine = nx * (y - startY) - ny * (x - startX) > 0d;
        if (leftOfStartButtCapLine) {
            return false;
        }

        // test whether x/y is to the left of the end butt cap line
        // FIXME use beyondButtCaps(pt.x, pt.y) instead?
        if (model.isDrawArrowheads() == false) {
            double endX = clipppedFlow.getEndPt().x;
            double endY = clipppedFlow.getEndPt().y;
            dx = clipppedFlow.cPtX - endX;
            dy = clipppedFlow.cPtY - endY;
            nx = -dy;
            ny = dx;
            boolean leftOfEndButtCapLine = nx * (y - endY) - ny * (x - endX) > 0d;
            if (leftOfEndButtCapLine) {
                return false;
            }
        } else {
            // test with arrowhead
            Arrow arrow = getArrow(model);
            if (arrow.hit(x, y)) {
                return true;
            }
        }

        // Get the distance of the point to the flow.
        double colinearTol = 0.2 /* px */ / model.getReferenceMapScale();
        double distanceSqWorld = clipppedFlow.distanceSquare(x, y, colinearTol);
        return distanceSqWorld <= maxDist * maxDist;
    }
}