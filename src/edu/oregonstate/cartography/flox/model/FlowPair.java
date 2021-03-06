package edu.oregonstate.cartography.flox.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

/**
 * Two flows with opposing directions connected to the same start node and end
 * node.
 *
 * @author Bernhard Jenny, School of Science, RMIT University, Melbourne
 */
/**
 * Every non static, non transient field in a JAXB-bound class will be
 * automatically bound to XML, unless annotated by @XmlTransient
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class FlowPair extends Flow {

    /**
     * Value of the second flow of this pair.
     */
    private final double value2;

    /**
     * shortening for end of second flow of this pair.
     */
    private double endShorteningToAvoidOverlaps2 = 0;

    /**
     * shortening for start of second flow of this pair.
     */
    protected double startShorteningToAvoidOverlaps2 = 0;

    private Flow cachedClippedCurve1IncludingArrow;
    private Flow cachedClippedCurve2IncludingArrow;
    private Flow cachedOffsetFlow1;
    private Flow cachedOffsetFlow2;

    /**
     * Constructor without parameters for JAXB only.
     */
    private FlowPair() {
        value2 = 0;
    }

    /**
     * Create a new FlowPair.
     *
     * @param flow1 flow connected to same start and end nodes as flow2, but in
     * opposite direction to flow2. flow1 cannot be locked.
     * @param flow2 flow connected to same start and end nodes as flow1, but in
     * opposite direction to flow1. flow2 cannot be locked.
     */
    public FlowPair(Flow flow1, Flow flow2) {
        // create a copy of flow1
        super(flow1.getStartPt(),
                // mean of the two control points
                (flow1.cPtX() + flow2.cPtX()) / 2, (flow1.cPtY() + flow2.cPtY()) / 2,
                flow1.getEndPt(),
                flow1.getValue());
        setStartClipArea(flow1.getStartClipArea());
        setEndClipArea(flow1.getEndClipArea());

        assert (flow1.isLocked() == false);
        assert (flow2.isLocked() == false);

        // store value of flow 2
        value2 = flow2.getValue();
    }

    /**
     * Copy constructor
     *
     * @param flowPair a FlowPair to copy
     */
    public FlowPair(FlowPair flowPair) {
        super(new Point(flowPair.getStartPt()),
                flowPair.cPtX(), flowPair.cPtY(),
                new Point(flowPair.getEndPt()),
                flowPair.getValue1(),
                flowPair.id);

        shallowCopyClipAreas(flowPair, this);
        setSelected(flowPair.isSelected());
        setLocked(flowPair.isLocked());
        value2 = flowPair.value2;
    }

    /**
     * Returns a copy of this FlowPair. The id of the new FlowPair is identical
     * to the id of this FlowPair.
     *
     * @return a copy
     */
    @Override
    public FlowPair copyFlow() {
        return new FlowPair(this);
    }

    /**
     * Overridden getValue returns sum of the two flow values of this FlowPair.
     *
     * @return
     */
    @Override
    public double getValue() {
        return getValue1() + getValue2();
    }

    /**
     * Value cannot be set for a FlowPair. An exception is thrown when this
     * method is called.
     *
     * @param value
     */
    @Override
    protected void setValue(double value) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the value of the first flow of this FlowPair.
     *
     * @return the value
     */
    public double getValue1() {
        return super.getValue();
    }

    /**
     * Returns the value of the second flow of this flow pair.
     *
     * @return the value
     */
    public double getValue2() {
        return value2;
    }

    @Override
    public void invalidateCachedValues() {
        super.invalidateCachedValues();
        cachedClippedCurve1IncludingArrow = null;
        cachedClippedCurve2IncludingArrow = null;
        cachedOffsetFlow1 = null;
        cachedOffsetFlow2 = null;
    }

    public Flow cachedOffsetFlow1(Model model) {
        if (cachedOffsetFlow1 == null) {
            cachedOffsetFlow1 = createOffsetFlow1(model, Flow.FlowOffsetting.LOW_QUALITY);
        }
        return cachedOffsetFlow1;
    }

    public Flow cachedOffsetFlow2(Model model) {
        if (cachedOffsetFlow2 == null) {
            cachedOffsetFlow2 = createOffsetFlow2(model, Flow.FlowOffsetting.LOW_QUALITY);
        }
        return cachedOffsetFlow2;
    }

    public Flow cachedClippedCurve1IncludingArrow(Model model) {
        if (cachedClippedCurve1IncludingArrow == null) {
            Flow f1 = cachedOffsetFlow1(model);
            cachedClippedCurve1IncludingArrow = model.clipFlowForComputations(f1);
        }
        return cachedClippedCurve1IncludingArrow;
    }

    public Flow cachedClippedCurve2IncludingArrow(Model model) {
        if (cachedClippedCurve2IncludingArrow == null) {
            Flow f2 = cachedOffsetFlow2(model);
            cachedClippedCurve2IncludingArrow = model.clipFlowForComputations(f2);
        }
        return cachedClippedCurve2IncludingArrow;
    }

    /**
     * Returns true if this FlowPair intersects with an obstacle.
     *
     * @param obstacle obstacle
     * @param model data model
     * @return
     */
    @Override
    public boolean cachedClippedCurveIncludingArrowIntersectsObstacle(Obstacle obstacle, Model model, int minObstacleDistPx) {

        // to accelerate this test, first test with the combined flow curve before creating offset flows, which is expensive
        if (super.cachedClippedCurveIncludingArrowIntersectsObstacle(obstacle, model, minObstacleDistPx) == false) {
            return false;
        }

        Flow flow1 = cachedClippedCurve1IncludingArrow(model);
        boolean intersection = flow1.cachedClippedCurveIncludingArrowIntersectsObstacle(obstacle, model, minObstacleDistPx);
        if (intersection == false) {
            Flow flow2 = cachedClippedCurve2IncludingArrow(model);
            intersection = flow2.cachedClippedCurveIncludingArrowIntersectsObstacle(obstacle, model, minObstacleDistPx);
        }
        return intersection;
    }

    /**
     * Tests whether this FlowPair intersects with another Flow. This is an
     * approximate test.
     *
     * @param flow flow to detect intersection with
     * @param model data model
     * @return true if the two flows intersect
     */
    @Override
    public boolean cachedClippedCurveIncludingArrowIntersects(Flow flow, Model model) {
        Point[] thatPolyline = flow.cachedClippedPolylineIncludingArrow(model);
        Point[] thisPolyline1 = cachedClippedCurve1IncludingArrow(model).cachedPolyline(model);
        if (polylinesIntersect(thatPolyline, thisPolyline1)) {
            return true;
        }
        Point[] thisPolyline2 = cachedClippedCurve2IncludingArrow(model).cachedPolyline(model);
        return polylinesIntersect(thatPolyline, thisPolyline2);
    }

    /**
     * Tests whether this FlowPair intersects with another FlowPair. This is an
     * approximate test.
     *
     * @param flowPair FlowPair to detect intersection with
     * @return true if the two flows intersect
     */
    @Override
    public boolean cachedClippedCurvedIncludingArrowIntersects(FlowPair flowPair, Model model) {
        Point[] thisPolyline1 = cachedClippedCurve1IncludingArrow(model).cachedPolyline(model);
        Point[] thatPolyline1 = flowPair.cachedClippedCurve1IncludingArrow(model).cachedPolyline(model);
        if (polylinesIntersect(thisPolyline1, thatPolyline1)) {
            return true;
        }
        Point[] thatPolyline2 = flowPair.cachedClippedCurve2IncludingArrow(model).cachedPolyline(model);
        if (polylinesIntersect(thisPolyline1, thatPolyline2)) {
            return true;
        }
        Point[] thisPolyline2 = cachedClippedCurve2IncludingArrow(model).cachedPolyline(model);
        if (polylinesIntersect(thisPolyline2, thatPolyline1)) {
            return true;
        }
        return polylinesIntersect(thisPolyline2, thatPolyline2);
    }

    /**
     * Computes the offset in world coordinates for either of the two flows. The
     * offset is such that the sum of width of the two flows and the gap
     * in-between the two flows is centered on the axis of this flow.
     *
     * @param model model
     * @param forFlow1 true for flow 1, false for flow 2.
     * @return offset in world coordinates
     */
    private double offset(Model model, boolean forFlow1) {
        double value1 = getValue1();
        double width1 = model.getFlowWidthPx(value1);
        double width2 = model.getFlowWidthPx(value2);
        double gap = model.getParallelFlowsGapPx();
        double totalWidth = width1 + width2 + gap;
        if (forFlow1) {
            return (totalWidth - width1) / 2 / model.getReferenceMapScale();
        } else {
            return (totalWidth - width2) / 2 / model.getReferenceMapScale();
        }
    }

    /**
     * Returns a new instance of the Flow class that can be used to draw the
     * first of the two flows of this FlowPair. The returned flow is offset from
     * the center line of this flow. <STRONG>The start node and end node of the
     * returned flow do not align with the nodes of this FlowPair. The nodes
     * have been moved to create a nice parallel line.</STRONG>
     *
     * @param model data model
     * @param quality quality setting for offsetting flow.
     * @return a new flow
     */
    public Flow createOffsetFlow1(Model model, Flow.FlowOffsetting quality) {
        Flow flow = new Flow(this);
        flow.setValue(getValue1());
        flow.offsetFlow(offset(model, true), model, quality);
        return flow;
    }

    /**
     * Returns a new instance of the Flow class that can be used to draw the
     * second of the two flows of this FlowPair. The returned flow is offset
     * from the center line of this flow. <STRONG>The start node and end node of
     * the returned flow do not align with the nodes of this FlowPair. The nodes
     * have been moved to create a nice parallel line.</STRONG>
     *
     * @param model data model
     * @param quality quality setting for offsetting flow.
     * @return a new flow
     */
    public Flow createOffsetFlow2(Model model, Flow.FlowOffsetting quality) {
        Flow flow = new Flow(this);
        flow.setValue(getValue2());
        flow.reverseFlow();
        flow.startShorteningToAvoidOverlaps = startShorteningToAvoidOverlaps2;
        flow.endShorteningToAvoidOverlaps = endShorteningToAvoidOverlaps2;
        flow.offsetFlow(offset(model, false), model, quality);
        return flow;
    }

    /**
     * Returns a new instance of the Flow class representing the first flow of
     * this FlowPair. The returned flow is not offset from the center line of
     * this flow.
     *
     * @return a new flow
     */
    public Flow createFlow1() {
        Flow flow = new Flow(this);
        flow.setValue(getValue1());
        return flow;
    }

    /**
     * Returns a new instance of the Flow class representing the second flow of
     * this FlowPair. The returned flow is not offset from the center line of
     * this flow.
     *
     * @return a new flow
     */
    public Flow createFlow2() {
        Flow flow = new Flow(this);
        flow.setValue(getValue2());
        flow.reverseFlow();
        flow.endShorteningToAvoidOverlaps = endShorteningToAvoidOverlaps2;
        flow.startShorteningToAvoidOverlaps = startShorteningToAvoidOverlaps2;
        return flow;
    }

    @Override
    public void resetShortening() {
        super.resetShortening();
        endShorteningToAvoidOverlaps2 = 0;
        startShorteningToAvoidOverlaps2 = 0;
    }

    /**
     * Tests whether this Flow overlaps with an arrow. The arrow is treated as a
     * triangle consisting of the tip point and the two corner points. The flow
     * width is taken into account.
     *
     * @param arrow arrow to test with
     * @return true if there is an overlap, false otherwise.
     */
    @Override
    public boolean isOverlappingArrow(Arrow arrow, Model model) {
        Flow flow1 = createOffsetFlow1(model, FlowOffsetting.HIGH_QUALITY);
        if (flow1.isOverlappingArrow(arrow, model)) {
            return true;
        }

        Flow flow2 = createOffsetFlow2(model, FlowOffsetting.HIGH_QUALITY);
        return flow2.isOverlappingArrow(arrow, model);
    }

    /**
     * Tests whether a passed Arrow overlaps with any of the two Arrows of this
     * Flow.
     *
     * The two arrows are treated as triangles consisting of the tip point and
     * the two corner points.
     *
     * @param arrow arrow to test with
     * @param model the Model with all flows
     * @return true if there is an overlap, false otherwise.
     */
    @Override
    public boolean isArrowOverlappingArrow(Arrow arrow, Model model) {
        Flow flow1 = createOffsetFlow1(model, FlowOffsetting.HIGH_QUALITY);
        if (flow1.isArrowOverlappingArrow(arrow, model)) {
            return true;
        }

        Flow flow2 = createOffsetFlow2(model, FlowOffsetting.HIGH_QUALITY);
        return flow2.isArrowOverlappingArrow(arrow, model);
    }

    /**
     * Copy shortening values from the passed Flow to this Flow.
     *
     * @param flow flow to copy from
     */
    @Override
    public void updateShortening(Flow flow) {
        super.updateShortening(flow);
        if (flow instanceof FlowPair) {
            FlowPair flowPair = (FlowPair) flow;
            endShorteningToAvoidOverlaps2 = flowPair.endShorteningToAvoidOverlaps2;
            startShorteningToAvoidOverlaps2 = flowPair.startShorteningToAvoidOverlaps2;
        }
    }

    public void updateShorteningFlow2(double startShorteningToAvoidOverlaps2,
            double endShorteningToAvoidOverlaps2) {
        this.endShorteningToAvoidOverlaps2 = endShorteningToAvoidOverlaps2;
        this.startShorteningToAvoidOverlaps2 = startShorteningToAvoidOverlaps2;
    }

    /**
     * Test whether the passed point hits this flow line or the arrowhead.
     *
     * @param x x coordinate
     * @param y y coordinate
     * @param tolerance the point x/y can miss the flow line by this much and
     * will still be considered on the line.
     * @param model model with all flows
     * @param clipNodes if true nodes are clipped off this flow before hit
     * testing
     * @return true if the flow line is hit, false otherwise.
     */
    @Override
    public boolean hit(double x, double y, double tolerance, Model model, boolean clipNodes) {
        Flow flow1 = createOffsetFlow1(model, FlowOffsetting.HIGH_QUALITY);
        if (flow1.hit(x, y, tolerance, model, clipNodes)) {
            return true;
        }

        Flow flow2 = createOffsetFlow2(model, FlowOffsetting.HIGH_QUALITY);
        return flow2.hit(x, y, tolerance, model, clipNodes);
    }

    /**
     * Returns the ratio between the length of the base line of flow trunk (that
     * is, the flow without the arrowhead) and the length of the visible flow
     * including the arrowhead.
     *
     * Returns the larger of the two ratios for the two parallel flows.
     *
     * @param model model
     * @return ratio between 0 (there is no trunk or the flow has a length of 0)
     * to 1 (there is no arrowhead).
     */
    @Override
    public double flowTrunkToFlowRatio(Model model) {
        Flow flow1 = createOffsetFlow1(model, FlowOffsetting.HIGH_QUALITY);
        double r1 = flow1.flowTrunkToFlowRatio(model);
        Flow flow2 = createOffsetFlow2(model, FlowOffsetting.HIGH_QUALITY);
        double r2 = flow2.flowTrunkToFlowRatio(model);
        return Math.max(r1, r2);
    }
    
    @Override
    public void adjustEndShorteningToAvoidOverlaps(Model model) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void adjustStartShorteningToAvoidOverlaps(Model model) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void offsetFlow(double offset, Model model, FlowOffsetting quality) {
        throw new UnsupportedOperationException();
    }

    // FIXME 
//    @Override
//    public Flow[] split(double t) {
//        throw new UnsupportedOperationException();
//    }
    @Override
    protected Arrow getArrow(Model model, double arrowTipClipRadius) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Flow clipAroundStartNode(double r1, double r2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Flow clipAroundEndNode(double r1, double r2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Point[] intersections(Flow flow) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isIntersectingLineSegment(double x1, double y1, double x2, double y2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOverlappingAnyFlowAtPoint(double t, Model model) {
        throw new UnsupportedOperationException();
    }

    // FIXME
//    @Override
//    public boolean isOverlappingObstacle(Obstacle obstacle, Model model, int minObstaclesDistPx) {
//        throw new UnsupportedOperationException();
//    }   
}
