package edu.oregonstate.cartography.flox.model;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollectionIterator;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.io.WKTWriter;
import edu.oregonstate.cartography.utils.GeometryUtils;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

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
     * clip area for the start of the flow.
     */
    @XmlJavaTypeAdapter(GeometrySerializer.class)
    private Geometry startClipArea;

    /**
     * startClipArea serialized to WKT format
     */
    private String startClipAreaWKT;
    /**
     * clip area for the end of the flow.
     */
    @XmlJavaTypeAdapter(GeometrySerializer.class)
    private Geometry endClipArea;

    /**
     * endClipArea serialized to WKT format
     */
    private String endClipAreaWKT;

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
     *
     * <STRONG>The polyline is not initialized or updated by this Flow. It is
     * the responsibility of the user to update the polyline when any of the
     * following change: start point, end point, control point, startClipArea,
     * endClipArea, size of nodes, gap between start and end of line and
     * nodes.</STRONG> Note that a change to the arrowhead geometry does not
     * require an update to the polyline.
     */
    protected Point[] polyline;

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
        this.id = createID();
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
     * Copy constructor. Creates deep copies of points. Creates shallow copies
     * of clip areas.
     *
     * @param flow Flow to copy
     */
    public Flow(Flow flow) {
        this(new Point(flow.startPt),
                flow.cPtX, flow.cPtY,
                new Point(flow.endPt),
                flow.value);
        shallowCopyClipAreas(flow, this);
        selected = flow.selected;
        locked = flow.locked;
    }

    /**
     * Returns a copy of this Flow. The id of the new flow is unique. Creates
     * deep copies of points. Creates shallow copies of clip areas.
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
        dst.endClipArea = src.endClipArea;
        dst.endClipAreaWKT = src.endClipAreaWKT;
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

    /**
     * Updates the cached polyline. The polyline is not initialized or updated
     * by this Flow. It is the responsibility of the user to update the polyline
     * when any of the following change: start point, end point, control point,
     * startClipArea, endClipArea, size of nodes, gap between start/end of line
     * and nodes. A change to the arrowhead geometry does not require an update
     * to the polyline.
     *
     * @param model data model
     * @param segmentLength the target segment length. The actual length will
     * differ.
     */
    public void updateCachedPolylineApproximation(Model model, double segmentLength) {
        Flow clippedFlow = model.clipFlow(this, false, true);
        ArrayList<Point> points = clippedFlow.regularIntervals(segmentLength);
        polyline = points.toArray(new Point[points.size()]);
    }

    /**
     * Returns the cached polyline. The polyline is not initialized or updated
     * by this Flow. It is the responsibility of the user to update the polyline
     * by calling updateCachedPolylineApproximation() when any of the following
     * change: start point, end point, control point, startClipArea,
     * endClipArea, size of nodes, gap between start/end of line and nodes. A
     * change to the arrowhead geometry does not require an update to the
     * polyline.
     *
     * @return a reference to the polyline approximating the geometry of this
     * flow. Can be null or inaccurate if getCachedPolylineApproximation() has
     * never been called or if the geometry changed since the last call to
     * getCachedPolylineApproximation().
     */
    public Point[] getCachedPolylineApproximation() {
        return polyline;
    }

    /**
     * Tests whether this Flow intersects with another Flow. This is an
     * approximate test.
     *
     * <STRONG>updateCachedPolylineApproximation() needs to be called before
     * this method can be called.</STRONG>
     *
     * @param flow flow to detect intersection with
     * @return true if the two flows intersect
     */
    public boolean intersects(Flow flow) {
        Point[] thisPolyline = getCachedPolylineApproximation();
        Point[] thatPolyline = flow.getCachedPolylineApproximation();
        return polylinesIntersect(thisPolyline, thatPolyline);
    }

    /**
     * Tests whether this Flow intersects with another FlowPair. This is an
     * approximate test.
     *
     * <STRONG>updateCachedPolylineApproximation() needs to be called before
     * this method can be called.</STRONG>
     *
     * @param flowPair FlowPair to detect intersection with
     * @return true if the two flows intersect
     */
    public boolean intersects(FlowPair flowPair) {
        Point[] thisPolyline = getCachedPolylineApproximation();
        Point[] thatPolyline1 = flowPair.getCachedPolylineApproximation();
        if (polylinesIntersect(thisPolyline, thatPolyline1)) {
            return true;
        }
        Point[] thatPolyline2 = flowPair.getCachedPolylineApproximation2();
        return polylinesIntersect(thisPolyline, thatPolyline2);
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
     * Returns the distance between start and end point
     *
     * @return
     */
    public double getBaselineLength() {
        double dx = startPt.x - endPt.x;
        double dy = startPt.y - endPt.y;
        return Math.sqrt(dx * dx + dy * dy);
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
        return Math.atan2(dy, dx);
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
     * Inverse start and end point.
     *
     * @param model model (used for finding start and end clipping areas)
     */
    public void reverseFlow(Model model) {
        Point tempPt = startPt;
        startPt = endPt;
        endPt = tempPt;

        if (model.isClipFlowStarts() && model.isClipFlowEnds()) {
            // swap clipping geometry
            Geometry tempGeometry = startClipArea;
            startClipArea = endClipArea;
            endClipArea = tempGeometry;

            String tempWKT = startClipAreaWKT;
            startClipAreaWKT = endClipAreaWKT;
            endClipAreaWKT = tempWKT;
        } else if (model.isClipFlowStarts()) {
            model.updateStartClipArea(this);
        } else if (model.isClipFlowEnds()) {
            model.updateEndClipArea(this);
        }
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
        } else {
            startClipAreaWKT = null;
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
        } else {
            endClipAreaWKT = null;
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
    }

    public void offsetCtrlPt(double dx, double dy) {
        cPtX += dx;
        cPtY += dy;
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
     * Offsets this flow in parallel to the line.
     *
     * @param offset offset distance
     * @param model data model
     */
    public void offsetFlow(double offset, Model model) {
        // number of iterations
        final int nbrIterations = 10;
        // number of samples along the curves for computing distances
        final int nbrTSamples = 10;
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
        double dx = startX - endX;
        double dy = startY - endY;
        double baselineLength = Math.sqrt(dx * dx + dy * dy);
        double scale = baselineLength / getBaselineLength();
        double originalBaselineOrientation = getBaselineOrientation();
        double baselineOrientation = Math.atan2(endY - startY, endX - startX);
        double rot = baselineOrientation - originalBaselineOrientation;
        ctrlPtX = scale * (cPtX - startPt.x);
        ctrlPtY = scale * (cPtY - startPt.y);
        double cos = Math.cos(rot);
        double sin = Math.sin(rot);
        double newX = ctrlPtX * cos - ctrlPtY * sin;
        double newY = ctrlPtX * sin + ctrlPtY * cos;
        ctrlPtX = newX + startX;
        ctrlPtY = newY + startY;

        // improve the control point position such that the distance between the
        // original curve and the offset curve are approximately constant
        for (int i = 0; i < nbrIterations; i++) {
            Flow offsetFlow = new Flow(new Point(startX, startY),
                    ctrlPtX, ctrlPtY,
                    new Point(endX, endY), 1d);
            double moveX = 0;
            double moveY = 0;
            for (int j = 0; j < nbrTSamples; j++) {
                double t = (j + 0.5) * (1. / nbrTSamples);

                // Move along the offset curve and find closest points on the original curve.
                // This results in a curve without excessive curvature, but 
                // the offset curve is too distant from the original curve where 
                // the original curve is strongly bent
                Point ptOnOffsetCurve1 = offsetFlow.pointOnCurve(t);
                Point ptOnOriginalCurve1 = closestPointOnCurve(ptOnOffsetCurve1);
                double curveDistance1 = ptOnOffsetCurve1.distance(ptOnOriginalCurve1);
                double gap1 = Math.abs(offset) - curveDistance1;
                double gapW1 = gap1 * w1;

                // direction of control point movement
                double dirX1 = ptOnOffsetCurve1.x - ptOnOriginalCurve1.x;
                double dirY1 = ptOnOffsetCurve1.y - ptOnOriginalCurve1.y;
                double dCtrl1 = Math.sqrt(dirX1 * dirX1 + dirY1 * dirY1);
                if (dCtrl1 > 0) {
                    dirX1 /= dCtrl1;
                    dirY1 /= dCtrl1;
                    moveX += dirX1 * gapW1;
                    moveY += dirY1 * gapW1;
                }

                // Move along the offset curve and find closest points on the original curve.
                // This results in a curve with approximately correct distance to the original curve,
                // but curves tend to by curvy where the original curve is strongly bent
                Point ptOnOriginalCurve2 = pointOnCurve(t);
                Point ptOnOffsetCurve2 = offsetFlow.closestPointOnCurve(ptOnOriginalCurve2);
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
        assert (t >= 0d && t <= 1d);

        double w3 = t * t;
        double _1_t = 1 - t;
        double w2 = 2 * _1_t * t;
        double w1 = _1_t * _1_t;
        double x = startPt.x * w1 + cPtX * w2 + endPt.x * w3;
        double y = startPt.y * w1 + cPtY * w2 + endPt.y * w3;
        return new Point(x, y);
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
    public double distanceSq(double x, double y, double tol) {
        return GeometryUtils.getDistanceToQuadraticBezierCurveSq(startPt.x, startPt.y,
                cPtX, cPtY, endPt.x, endPt.y, tol, x, y);
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
        return Math.atan2(dy, dx);
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
        return Math.atan2(dy, dx);
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
     * Computes geometry of arrow heads
     *
     * @param model model
     * @param endClipRadius the tip of the arrow is placed at this distance from
     * the end of the flow
     * @return a new Arrow instance
     */
    public Arrow getArrow(Model model, double endClipRadius) {
        return new Arrow(model, this, endClipRadius);
    }

    public Arrow getArrow(Model model) {
        double endClipRadius = model.endClipRadius(this, false, null, true);
        return new Arrow(model, this, endClipRadius);
    }

}
