package edu.oregonstate.cartography.flox.gui;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryCollection;
import edu.oregonstate.cartography.flox.model.BooleanGrid;
import edu.oregonstate.cartography.flox.model.CSVFlowExporter;
import edu.oregonstate.cartography.flox.model.Flow;
import edu.oregonstate.cartography.flox.model.FlowImporter;
import edu.oregonstate.cartography.flox.model.Force;
import edu.oregonstate.cartography.flox.model.ForceLayouter;
import edu.oregonstate.cartography.flox.model.Layer;
import edu.oregonstate.cartography.flox.model.LayoutGrader;
import edu.oregonstate.cartography.flox.model.Model;
import edu.oregonstate.cartography.flox.model.Model.FlowNodeDensity;
import edu.oregonstate.cartography.flox.model.Point;
import edu.oregonstate.cartography.flox.model.SVGFlowExporter;
import edu.oregonstate.cartography.flox.model.VectorSymbol;
import edu.oregonstate.cartography.map.AddFlowTool;
import edu.oregonstate.cartography.map.MeasureTool;
import edu.oregonstate.cartography.map.PanTool;
import edu.oregonstate.cartography.map.ScaleMoveSelectionTool;
import edu.oregonstate.cartography.map.ZoomInTool;
import edu.oregonstate.cartography.map.ZoomOutTool;
import edu.oregonstate.cartography.simplefeature.ShapeGeometryImporter;
import edu.oregonstate.cartography.utils.FileUtils;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.xml.bind.JAXBException;

/**
 *
 * @author Bernhard Jenny, Cartography and Geovisualization Group, Oregon State
 * University
 */
public class MainWindow extends javax.swing.JFrame {

    /**
     * the model of this application
     */
    private Model model;

    /**
     * Undo/redo manager.
     */
    private final Undo undo;

    private boolean updatingGUI = false;

    private LayoutWorker layoutWorker = null;

    /**
     * Creates new form MainWindow
     */
    public MainWindow() {

        initComponents();
        progressBar.setVisible(false);

        // change the name of a layer
        new ListAction(layerList, new EditListAction() {
            @Override
            protected void applyValueToModel(String value, ListModel model, int row) {
                DnDListModel m = (DnDListModel) model;
                Layer layer = (Layer) m.get(row);
                layer.setName(value);
            }
        });

        // reorder layers
        layerList.addPropertyChangeListener(DraggableList.MODEL_PROPERTY,
                new PropertyChangeListener() {

            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                model.removeAllLayers();
                DnDListModel m = (DnDListModel) layerList.getModel();
                int n = m.getSize();
                for (int i = 0; i < n; i++) {
                    model.addLayer((Layer) m.get(i));
                }
                mapComponent.repaint();
            }
        });
        mapComponent.addMouseMotionListener(coordinateInfoPanel);
        mapComponent.setMainWindow(this);
        mapComponent.requestFocusInWindow();

        try {
            this.undo = new Undo(new Model().marshal());
            undo.registerUndoMenuItems(undoMenuItem, redoMenuItem);
        } catch (JAXBException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Shows a dialog with an error message, and logs error to default Logger.
     *
     * @param msg The message to display.
     * @param ex An optional exception with additional information.
     */
    private void showErrorDialog(String msg, Throwable ex) {
        Logger.getLogger(MainWindow.class.getName()).log(Level.SEVERE, null, ex);
        String title = "Flox Error";
        ErrorDialog.showErrorDialog(msg, title, ex, this);
    }

    protected void registerUndoMenuItems(JMenuItem undoMenuItem, JMenuItem redoMenuItem) {
        undo.registerUndoMenuItems(undoMenuItem, redoMenuItem);
    }

    private void undoRedo(boolean undoFlag) {
        Object undoData = undoFlag ? undo.getUndo() : undo.getRedo();
        if (undoData != null) {
            try {
                Model newModel = Model.unmarshal((byte[]) undoData);
                // copy map from previous model (changes to map layers and 
                // layer styles are not undoable).
                model.copyTransientFields(newModel);
                setModel(newModel);
                layout(null);
            } catch (Throwable ex) {
                showErrorDialog("Could not undo or redo the command.", ex);
            }
        }
    }

    protected void addUndo(String message) {
        try {
            if (updatingGUI == false) {
                undo.add(message, model.marshal());
            }
        } catch (JAXBException ex) {
            Logger.getLogger(MainWindow.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Set the model for this application.
     *
     * @param model
     */
    public void setModel(Model model) {
        assert (model != null);
        this.model = model;
        mapComponent.setModel(model);
        mapComponent.refreshMap();
        writeModelToGUI();

        //FIXME
        //This activates the ScaleMoveSelectTool when settings are uploaded. 
        //This was put here to activate the tool at startup, but it also
        //activates the tool anytime settings are imported. There's probably
        //a better place to put this.
        arrowToggleButton.doClick();
    }

    /**
     * Write the data values from the model to the GUI elements
     */
    private void writeModelToGUI() {
        // TODO could disable GUI elements when no flows are defined

        if (model == null) {
            return;
        }

        updatingGUI = true;
        try {
            // Arrow Settings
            flowDistanceFromEndPointFormattedTextField.setValue(model.getFlowDistanceFromEndPointPixel());
            flowDistanceFromStartPointFormattedTextField.setValue(model.getFlowDistanceFromStartPointPixel());
            addArrowsCheckbox.setSelected(model.isDrawArrows());
            arrowheadLengthSlider.setValue((int) (model.getArrowLengthScaleFactor() * 40));
            arrowheadWidthSlider.setValue((int) (model.getArrowWidthScaleFactor() * 40));
            arrowEdgeCtrlLengthSlider.setValue((int) (model.getArrowEdgeCtrlLength() * 100));
            arrowEdgeCtrlWidthSlider.setValue((int) (model.getArrowEdgeCtrlWidth() * 100));
            arrowCornerPositionSlider.setValue((int) (model.getArrowCornerPosition() * 100));
            arrowSizeRatioSlider.setValue((int) (model.getArrowSizeRatio() * 100));
            arrowLengthRatioSlider.setValue((int) Math.abs((model.getArrowLengthRatio() * 100) - 100));
            maximumFlowWidthSlider.setValue((int) model.getMaxFlowStrokeWidthPixel());
            maximumNodeSizeSlider.setValue((int) model.getMaxNodeSizePx());

            // Force Settings
            enforceRangeboxCheckbox.setSelected(model.isEnforceRangebox());
            longestFlowStiffnessSlider.setValue((int) (model.getMaxFlowLengthSpringConstant() * 100d));
            zeroLengthStiffnessSlider.setValue((int) (model.getMinFlowLengthSpringConstant() * 100d));

            int[] v = {0, 1, 2, 4, 6, 8, 16, 32};
            int w = model.getDistanceWeightExponent();
            for (int i = 0; i < v.length; i++) {
                if (w == v[i]) {
                    exponentSlider.setValue(i);
                }
            }

            nodeWeightSlider.setValue((int) (model.getNodesWeight() * 10d));
            antiTorsionSlider.setValue((int) (model.getAntiTorsionWeight() * 100d));
            peripheralStiffnessSlider.setValue((int) (model.getPeripheralStiffnessFactor() * 100));
            canvasSizeSlider.setValue((int) (model.getCanvasPadding() * 100));
            flowRangeboxSizeSlider.setValue((int) (model.getFlowRangeboxHeight() * 100));
            if (model.getFlowNodeDensity() == FlowNodeDensity.LOW) {
                lowFlowSegmentationMenuItem.setSelected(true);
            } else if (model.getFlowNodeDensity() == FlowNodeDensity.MEDIUM) {
                mediumFlowSegmentationMenuItem.setSelected(true);
            } else {
                highFlowSegmentationMenuItem.setSelected(true);
            }
            angularDistributionSlider.setValue((int) (model.getAngularDistributionWeight() * 100));

            // clipping
            boolean hasFlowsAndClipAreas = model.hasClipAreas() && model.getNbrFlows() > 0;
            boolean clipStart = model.isClipFlowStarts();
            boolean clipEnd = model.isClipFlowEnds();

            clipWithStartAreasCheckBox.setSelected(clipStart);
            clipWithEndAreasCheckBox.setSelected(clipEnd);

            clipWithEndAreasCheckBox.setEnabled(hasFlowsAndClipAreas);
            clipWithStartAreasCheckBox.setEnabled(hasFlowsAndClipAreas);

            endAreasBufferDistanceFormattedTextField.setEnabled(hasFlowsAndClipAreas && clipEnd);
            startAreasBufferDistanceFormattedTextField.setEnabled(hasFlowsAndClipAreas && clipStart);

            endAreasBufferDistanceFormattedTextField.setValue(
                    model.getEndClipAreaBufferDistance());
            startAreasBufferDistanceFormattedTextField.setValue(
                    model.getStartClipAreaBufferDistance());

            drawEndClipAreasCheckBox.setEnabled(hasFlowsAndClipAreas && clipEnd);
            drawStartClipAreasCheckBox.setEnabled(hasFlowsAndClipAreas && clipStart);

            // reference scale
            lockFeatureScaleToggleButton.setSelected(model.isScaleLocked());

        } finally {
            updatingGUI = false;
        }
    }

    private void updateLayerList() {
        assert SwingUtilities.isEventDispatchThread();
        int selectedID = layerList.getSelectedIndex();
        layerList.setListData(model.getLayers().toArray());
        layerList.setSelectedIndex(selectedID);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {
        java.awt.GridBagConstraints gridBagConstraints;

        mapToolsButtonGroup = new javax.swing.ButtonGroup();
        importPanel = new javax.swing.JPanel();
        jLabel22 = new javax.swing.JLabel();
        jLabel23 = new javax.swing.JLabel();
        pointsFilePathLabel = new javax.swing.JLabel();
        flowsFilePathLabel = new javax.swing.JLabel();
        selectPointsFileButton = new javax.swing.JButton();
        selectFlowsFileButton = new javax.swing.JButton();
        importPanelOKButton = new javax.swing.JButton();
        importPanelCancelButton = new javax.swing.JButton();
        buttonGroup1 = new javax.swing.ButtonGroup();
        jCheckBoxMenuItem1 = new javax.swing.JCheckBoxMenuItem();
        jToolBar1 = new javax.swing.JToolBar();
        jPanel2 = new javax.swing.JPanel();
        arrowToggleButton = new javax.swing.JToggleButton();
        addFlowToggleButton = new javax.swing.JToggleButton();
        lockUnlockButton = new javax.swing.JButton();
        zoomInToggleButton = new javax.swing.JToggleButton();
        zoomOutToggleButton = new javax.swing.JToggleButton();
        handToggleButton = new javax.swing.JToggleButton();
        distanceToggleButton = new javax.swing.JToggleButton();
        showAllButton = new javax.swing.JButton();
        coordinateInfoPanel = new edu.oregonstate.cartography.flox.gui.CoordinateInfoPanel();
        vallueLabel = new javax.swing.JLabel();
        valueFormattedTextField = new javax.swing.JFormattedTextField();
        jLabel12 = new javax.swing.JLabel();
        xFormattedTextField = new javax.swing.JFormattedTextField();
        jLabel25 = new javax.swing.JLabel();
        yFormattedTextField = new javax.swing.JFormattedTextField();
        mapComponent = new edu.oregonstate.cartography.flox.gui.FloxMapComponent();
        rightPanel = new javax.swing.JPanel();
        controlsTabbedPane = new javax.swing.JTabbedPane();
        forcesPanel = new TransparentMacPanel();
        javax.swing.JLabel jLabel3 = new javax.swing.JLabel();
        exponentSlider = new javax.swing.JSlider();
        javax.swing.JLabel jLabel4 = new javax.swing.JLabel();
        longestFlowStiffnessSlider = new javax.swing.JSlider();
        zeroLengthStiffnessSlider = new javax.swing.JSlider();
        javax.swing.JLabel jLabel5 = new javax.swing.JLabel();
        javax.swing.JLabel jLabel6 = new javax.swing.JLabel();
        nodeWeightSlider = new javax.swing.JSlider();
        javax.swing.JLabel jLabel7 = new javax.swing.JLabel();
        antiTorsionSlider = new javax.swing.JSlider();
        javax.swing.JLabel jLabel8 = new javax.swing.JLabel();
        peripheralStiffnessSlider = new javax.swing.JSlider();
        enforceRangeboxCheckbox = new javax.swing.JCheckBox();
        canvasSizeSlider = new javax.swing.JSlider();
        jLabel11 = new javax.swing.JLabel();
        flowRangeboxSizeSlider = new javax.swing.JSlider();
        jLabel13 = new javax.swing.JLabel();
        viewCanvasToggleButton = new javax.swing.JToggleButton();
        viewFlowRangeboxToggleButton = new javax.swing.JToggleButton();
        angularDistributionSlider = new javax.swing.JSlider();
        javax.swing.JLabel jLabel28 = new javax.swing.JLabel();
        limitNodesRepulsionToBandCheckBox = new javax.swing.JCheckBox();
        minPxDistanceOfFlowsFromNodesSlider = new javax.swing.JSlider();
        minPxDistanceOfFlowsFromNodesSliderLabel = new javax.swing.JLabel();
        mapPanel = new TransparentMacPanel();
        mapControlPanel = new TransparentMacPanel();
        javax.swing.JLabel jLabel9 = new javax.swing.JLabel();
        flowDistanceFromEndPointFormattedTextField = new javax.swing.JFormattedTextField();
        jLabel29 = new javax.swing.JLabel();
        flowDistanceFromStartPointFormattedTextField = new javax.swing.JFormattedTextField();
        jLabel14 = new javax.swing.JLabel();
        layerListScrollPane = new javax.swing.JScrollPane();
        layerList = new edu.oregonstate.cartography.flox.gui.DraggableList();
        symbolPanel = new TransparentMacPanel();
        fillCheckBox = new javax.swing.JCheckBox();
        strokeCheckBox = new javax.swing.JCheckBox();
        fillColorButton = new edu.oregonstate.cartography.flox.gui.ColorButton();
        strokeColorButton = new edu.oregonstate.cartography.flox.gui.ColorButton();
        addLayerButton = new javax.swing.JButton();
        jButton1 = new javax.swing.JButton();
        maximumFlowWidthSlider = new javax.swing.JSlider();
        jLabel26 = new javax.swing.JLabel();
        maximumNodeSizeSlider = new javax.swing.JSlider();
        jLabel27 = new javax.swing.JLabel();
        showNodesToggleButton = new javax.swing.JToggleButton();
        showFlowsToggleButton = new javax.swing.JToggleButton();
        jSeparator9 = new javax.swing.JSeparator();
        lockFeatureScaleToggleButton = new javax.swing.JToggleButton();
        arrowHeadsPanel = new TransparentMacPanel();
        arrowHeadsControlPanel = new TransparentMacPanel();
        addArrowsCheckbox = new javax.swing.JCheckBox();
        arrowheadLengthSlider = new javax.swing.JSlider();
        jLabel10 = new javax.swing.JLabel();
        arrowheadWidthSlider = new javax.swing.JSlider();
        jLabel15 = new javax.swing.JLabel();
        jLabel16 = new javax.swing.JLabel();
        jLabel17 = new javax.swing.JLabel();
        arrowEdgeCtrlLengthSlider = new javax.swing.JSlider();
        arrowEdgeCtrlWidthSlider = new javax.swing.JSlider();
        arrowCornerPositionSlider = new javax.swing.JSlider();
        jLabel18 = new javax.swing.JLabel();
        jLabel19 = new javax.swing.JLabel();
        arrowSizeRatioSlider = new javax.swing.JSlider();
        jLabel30 = new javax.swing.JLabel();
        arrowLengthRatioSlider = new javax.swing.JSlider();
        useInFlowCheckbox = new javax.swing.JCheckBox();
        drawInlineArrowsCheckBox = new javax.swing.JCheckBox();
        clipAreaPanel = new TransparentMacPanel();
        clipAreaControlPanel = new TransparentMacPanel();
        javax.swing.JLabel jLabel20 = new javax.swing.JLabel();
        selectEndClipAreaButton = new javax.swing.JButton();
        javax.swing.JLabel jLabel21 = new javax.swing.JLabel();
        endAreasBufferDistanceFormattedTextField = new javax.swing.JFormattedTextField();
        clipWithEndAreasCheckBox = new javax.swing.JCheckBox();
        drawEndClipAreasCheckBox = new javax.swing.JCheckBox();
        javax.swing.JSeparator jSeparator5 = new javax.swing.JSeparator();
        javax.swing.JSeparator jSeparator6 = new javax.swing.JSeparator();
        javax.swing.JLabel jLabel24 = new javax.swing.JLabel();
        startAreasBufferDistanceFormattedTextField = new javax.swing.JFormattedTextField();
        drawStartClipAreasCheckBox = new javax.swing.JCheckBox();
        clipWithStartAreasCheckBox = new javax.swing.JCheckBox();
        jTextArea1 = new javax.swing.JTextArea();
        jTextArea2 = new javax.swing.JTextArea();
        jLabel1 = new javax.swing.JLabel();
        jLabel2 = new javax.swing.JLabel();
        progressBarPanel = new javax.swing.JPanel();
        progressBar = new javax.swing.JProgressBar();
        menuBar = new javax.swing.JMenuBar();
        fileMenu = new javax.swing.JMenu();
        importFlowsMenuItem = new javax.swing.JMenuItem();
        openPointsAndFlowsMenuItem = new javax.swing.JMenuItem();
        jSeparator14 = new javax.swing.JPopupMenu.Separator();
        openSettingsMenuItem = new javax.swing.JMenuItem();
        saveSettingsMenuItem = new javax.swing.JMenuItem();
        javax.swing.JPopupMenu.Separator jSeparator3 = new javax.swing.JPopupMenu.Separator();
        exportSVGMenuItem = new javax.swing.JMenuItem();
        exportImageMenuItem = new javax.swing.JMenuItem();
        javax.swing.JPopupMenu.Separator jSeparator4 = new javax.swing.JPopupMenu.Separator();
        exportFlowsCSVMenuItem = new javax.swing.JMenuItem();
        editMenu = new javax.swing.JMenu();
        undoMenuItem = new javax.swing.JMenuItem();
        redoMenuItem = new javax.swing.JMenuItem();
        jSeparator8 = new javax.swing.JPopupMenu.Separator();
        deleteMenuItem = new javax.swing.JMenuItem();
        selectAllMenuItem = new javax.swing.JMenuItem();
        selectNoneMenuItem = new javax.swing.JMenuItem();
        jSeparator10 = new javax.swing.JPopupMenu.Separator();
        lockMenuItem = new javax.swing.JMenuItem();
        unlockMenuItem = new javax.swing.JMenuItem();
        jSeparator11 = new javax.swing.JPopupMenu.Separator();
        reverseFlowDirectionMenuItem = new javax.swing.JMenuItem();
        straightenFlowsMenuItem = new javax.swing.JMenuItem();
        mapMenu = new javax.swing.JMenu();
        openShapefileMenuItem = new javax.swing.JMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        removeAllLayersMenuItem = new javax.swing.JMenuItem();
        removeSelectedLayerMenuItem = new javax.swing.JMenuItem();
        viewMenu = new javax.swing.JMenu();
        showAllMenuItem = new javax.swing.JMenuItem();
        showAllMenuItem1 = new javax.swing.JMenuItem();
        zoomOnSelectedLayerMenuItem = new javax.swing.JMenuItem();
        javax.swing.JPopupMenu.Separator viewSeparator = new javax.swing.JPopupMenu.Separator();
        viewZoomInMenuItem = new javax.swing.JMenuItem();
        viewZoomOutMenuItem = new javax.swing.JMenuItem();
        infoMenu = new javax.swing.JMenu();
        floxReportMenuItem = new javax.swing.JMenuItem();
        javax.swing.JPopupMenu.Separator jSeparator2 = new javax.swing.JPopupMenu.Separator();
        infoMenuItem = new javax.swing.JMenuItem();
        jMenu1 = new javax.swing.JMenu();
        flowSegmentationMenu = new javax.swing.JMenu();
        lowFlowSegmentationMenuItem = new javax.swing.JRadioButtonMenuItem();
        mediumFlowSegmentationMenuItem = new javax.swing.JRadioButtonMenuItem();
        highFlowSegmentationMenuItem = new javax.swing.JRadioButtonMenuItem();
        showFlowSegmentsMenuItem = new javax.swing.JMenuItem();
        enforceCanvasCheckBoxMenuItem = new javax.swing.JCheckBoxMenuItem();
        useFrictionCheckBoxMenuItem = new javax.swing.JCheckBoxMenuItem();
        useAngularFrictionCheckBoxMenuItem = new javax.swing.JCheckBoxMenuItem();
        moveFlowsCheckBoxMenuItem = new javax.swing.JCheckBoxMenuItem();
        jSeparator13 = new javax.swing.JPopupMenu.Separator();
        emptySpaceMenuItem = new javax.swing.JMenuItem();
        jSeparator7 = new javax.swing.JPopupMenu.Separator();
        recomputeMenuItem = new javax.swing.JMenuItem();
        liveDrawingCheckBoxMenuItem = new javax.swing.JCheckBoxMenuItem();

        importPanel.setLayout(new java.awt.GridBagLayout());

        jLabel22.setText("Nodes File");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_END;
        importPanel.add(jLabel22, gridBagConstraints);

        jLabel23.setText("Flows File");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_END;
        importPanel.add(jLabel23, gridBagConstraints);

        pointsFilePathLabel.setFont(pointsFilePathLabel.getFont().deriveFont(pointsFilePathLabel.getFont().getSize()-3f));
        pointsFilePathLabel.setText("�");
        pointsFilePathLabel.setPreferredSize(new java.awt.Dimension(500, 13));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 20, 0);
        importPanel.add(pointsFilePathLabel, gridBagConstraints);

        flowsFilePathLabel.setFont(flowsFilePathLabel.getFont().deriveFont(flowsFilePathLabel.getFont().getSize()-3f));
        flowsFilePathLabel.setText("�");
        flowsFilePathLabel.setPreferredSize(new java.awt.Dimension(500, 13));
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        importPanel.add(flowsFilePathLabel, gridBagConstraints);

        selectPointsFileButton.setText("Select�");
        selectPointsFileButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectPointsFileButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        importPanel.add(selectPointsFileButton, gridBagConstraints);

        selectFlowsFileButton.setText("Select�");
        selectFlowsFileButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectFlowsFileButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        importPanel.add(selectFlowsFileButton, gridBagConstraints);

        importPanelOKButton.setText("OK");
        importPanelOKButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importPanelOKButtonActionPerformed(evt);
            }
        });

        importPanelCancelButton.setText("Cancel");
        importPanelCancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importPanelCancelButtonActionPerformed(evt);
            }
        });

        jCheckBoxMenuItem1.setSelected(true);
        jCheckBoxMenuItem1.setText("jCheckBoxMenuItem1");

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        jToolBar1.setRollover(true);

        mapToolsButtonGroup.add(arrowToggleButton);
        arrowToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Arrow16x16.gif"))); // NOI18N
        arrowToggleButton.setToolTipText("Select, move and scale objects.");
        arrowToggleButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        arrowToggleButton.setPreferredSize(new java.awt.Dimension(24, 24));
        arrowToggleButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        arrowToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                arrowToggleButtonActionPerformed(evt);
            }
        });
        jPanel2.add(arrowToggleButton);

        mapToolsButtonGroup.add(addFlowToggleButton);
        addFlowToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/SetPoint16x16.gif"))); // NOI18N
        addFlowToggleButton.setToolTipText("Add Flow");
        addFlowToggleButton.setPreferredSize(new java.awt.Dimension(24, 24));
        addFlowToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addFlowToggleButtonActionPerformed(evt);
            }
        });
        jPanel2.add(addFlowToggleButton);

        lockUnlockButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Unlocked16x16.gif"))); // NOI18N
        lockUnlockButton.setToolTipText("Lock/Unlock Flows");
        lockUnlockButton.setBorderPainted(false);
        lockUnlockButton.setDisabledIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/LockDisabled16x16.gif"))); // NOI18N
        lockUnlockButton.setEnabled(false);
        lockUnlockButton.setPreferredSize(new java.awt.Dimension(24, 24));
        lockUnlockButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                lockUnlockButtonActionPerformed(evt);
            }
        });
        jPanel2.add(lockUnlockButton);

        mapToolsButtonGroup.add(zoomInToggleButton);
        zoomInToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/ZoomIn16x16.gif"))); // NOI18N
        zoomInToggleButton.setToolTipText("Zoom In");
        zoomInToggleButton.setFocusable(false);
        zoomInToggleButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        zoomInToggleButton.setPreferredSize(new java.awt.Dimension(24, 24));
        zoomInToggleButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        zoomInToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                zoomInToggleButtonActionPerformed(evt);
            }
        });
        jPanel2.add(zoomInToggleButton);

        mapToolsButtonGroup.add(zoomOutToggleButton);
        zoomOutToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/ZoomOut16x16.gif"))); // NOI18N
        zoomOutToggleButton.setToolTipText("Zoom Out");
        zoomOutToggleButton.setFocusable(false);
        zoomOutToggleButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        zoomOutToggleButton.setPreferredSize(new java.awt.Dimension(24, 24));
        zoomOutToggleButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        zoomOutToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                zoomOutToggleButtonActionPerformed(evt);
            }
        });
        jPanel2.add(zoomOutToggleButton);

        mapToolsButtonGroup.add(handToggleButton);
        handToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Hand16x16.gif"))); // NOI18N
        handToggleButton.setToolTipText("Pan");
        handToggleButton.setFocusable(false);
        handToggleButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        handToggleButton.setPreferredSize(new java.awt.Dimension(24, 24));
        handToggleButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        handToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                handToggleButtonActionPerformed(evt);
            }
        });
        jPanel2.add(handToggleButton);

        mapToolsButtonGroup.add(distanceToggleButton);
        distanceToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Ruler16x16.gif"))); // NOI18N
        distanceToggleButton.setToolTipText("Measure Distance and Angle");
        distanceToggleButton.setFocusable(false);
        distanceToggleButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        distanceToggleButton.setPreferredSize(new java.awt.Dimension(24, 24));
        distanceToggleButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        distanceToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                distanceToggleButtonActionPerformed(evt);
            }
        });
        jPanel2.add(distanceToggleButton);

        showAllButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/ShowAll20x14.png"))); // NOI18N
        showAllButton.setToolTipText("Show All");
        showAllButton.setBorderPainted(false);
        showAllButton.setContentAreaFilled(false);
        showAllButton.setFocusable(false);
        showAllButton.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        showAllButton.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
        showAllButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showAllButtonActionPerformed(evt);
            }
        });
        jPanel2.add(showAllButton);
        jPanel2.add(coordinateInfoPanel);

        vallueLabel.setFont(vallueLabel.getFont().deriveFont(vallueLabel.getFont().getSize()-2f));
        vallueLabel.setText("Value:");
        vallueLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 20, 0, 0));
        jPanel2.add(vallueLabel);

        valueFormattedTextField.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#,##0.######"))));
        valueFormattedTextField.setEnabled(false);
        valueFormattedTextField.setFont(valueFormattedTextField.getFont().deriveFont(valueFormattedTextField.getFont().getSize()-2f));
        valueFormattedTextField.setPreferredSize(new java.awt.Dimension(80, 28));
        valueFormattedTextField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                valueFormattedTextFieldActionPerformed(evt);
            }
        });
        jPanel2.add(valueFormattedTextField);

        jLabel12.setText("X:");
        jPanel2.add(jLabel12);

        xFormattedTextField.setEnabled(false);
        xFormattedTextField.setFont(new java.awt.Font("Lucida Grande", 0, 11)); // NOI18N
        xFormattedTextField.setPreferredSize(new java.awt.Dimension(100, 28));
        xFormattedTextField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                xFormattedTextFieldActionPerformed(evt);
            }
        });
        jPanel2.add(xFormattedTextField);

        jLabel25.setText("Y:");
        jPanel2.add(jLabel25);

        yFormattedTextField.setEnabled(false);
        yFormattedTextField.setFont(new java.awt.Font("Lucida Grande", 0, 11)); // NOI18N
        yFormattedTextField.setPreferredSize(new java.awt.Dimension(100, 28));
        yFormattedTextField.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                yFormattedTextFieldActionPerformed(evt);
            }
        });
        jPanel2.add(yFormattedTextField);

        jToolBar1.add(jPanel2);

        getContentPane().add(jToolBar1, java.awt.BorderLayout.NORTH);
        getContentPane().add(mapComponent, java.awt.BorderLayout.CENTER);

        rightPanel.setLayout(new java.awt.BorderLayout());

        forcesPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 10, 10));
        forcesPanel.setLayout(new java.awt.GridBagLayout());

        jLabel3.setText("Stiffness of Longest Flow");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel3, gridBagConstraints);

        exponentSlider.setMajorTickSpacing(1);
        exponentSlider.setMaximum(5);
        exponentSlider.setMinimum(1);
        exponentSlider.setPaintLabels(true);
        exponentSlider.setPaintTicks(true);
        exponentSlider.setSnapToTicks(true);
        exponentSlider.setValue(3);
        exponentSlider.setPreferredSize(new java.awt.Dimension(190, 38));
        {
            java.util.Hashtable labels = exponentSlider.createStandardLabels(exponentSlider.getMajorTickSpacing());
            java.util.Enumeration e = labels.elements();
            while(e.hasMoreElements()) {
                javax.swing.JComponent comp = (javax.swing.JComponent)e.nextElement();
                if (comp instanceof javax.swing.JLabel) {
                    javax.swing.JLabel label = (javax.swing.JLabel)(comp);
                    int i = Integer.parseInt(label.getText());
                    int p = (int)Math.round(Math.pow(2, i-1));
                    label.setText(Integer.toString(p));
                }
            }
            exponentSlider.setLabelTable(labels);
        }
        exponentSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                exponentSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 9;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        forcesPanel.add(exponentSlider, gridBagConstraints);

        jLabel4.setText("Weight Exponent");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 8;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel4, gridBagConstraints);

        longestFlowStiffnessSlider.setMajorTickSpacing(20);
        longestFlowStiffnessSlider.setMinorTickSpacing(10);
        longestFlowStiffnessSlider.setPaintLabels(true);
        longestFlowStiffnessSlider.setPaintTicks(true);
        longestFlowStiffnessSlider.setValue(0);
        longestFlowStiffnessSlider.setPreferredSize(new java.awt.Dimension(190, 38));
        longestFlowStiffnessSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                longestFlowStiffnessSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 4, 0);
        forcesPanel.add(longestFlowStiffnessSlider, gridBagConstraints);

        zeroLengthStiffnessSlider.setMajorTickSpacing(100);
        zeroLengthStiffnessSlider.setMaximum(500);
        zeroLengthStiffnessSlider.setMinorTickSpacing(50);
        zeroLengthStiffnessSlider.setPaintLabels(true);
        zeroLengthStiffnessSlider.setPaintTicks(true);
        zeroLengthStiffnessSlider.setValue(0);
        zeroLengthStiffnessSlider.setPreferredSize(new java.awt.Dimension(190, 38));
        zeroLengthStiffnessSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                zeroLengthStiffnessSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        forcesPanel.add(zeroLengthStiffnessSlider, gridBagConstraints);

        jLabel5.setText("Stiffness of Zero-Length Flow");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel5, gridBagConstraints);

        jLabel6.setText("Repulsion of Start and End Nodes");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 10;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel6, gridBagConstraints);

        nodeWeightSlider.setMajorTickSpacing(50);
        nodeWeightSlider.setMinorTickSpacing(10);
        nodeWeightSlider.setPaintLabels(true);
        nodeWeightSlider.setPaintTicks(true);
        nodeWeightSlider.setValue(0);
        nodeWeightSlider.setPreferredSize(new java.awt.Dimension(190, 38));
        nodeWeightSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                nodeWeightSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 11;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        forcesPanel.add(nodeWeightSlider, gridBagConstraints);

        jLabel7.setText("Anti-Torsion Forces");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 15;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel7, gridBagConstraints);

        antiTorsionSlider.setMajorTickSpacing(10);
        antiTorsionSlider.setMinorTickSpacing(5);
        antiTorsionSlider.setPaintLabels(true);
        antiTorsionSlider.setPaintTicks(true);
        antiTorsionSlider.setValue(100);
        antiTorsionSlider.setPreferredSize(new java.awt.Dimension(190, 38));
        antiTorsionSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                antiTorsionSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 16;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        forcesPanel.add(antiTorsionSlider, gridBagConstraints);

        jLabel8.setText("Stiffness Factor for Peripheral Flows");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 17;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel8, gridBagConstraints);

        peripheralStiffnessSlider.setMajorTickSpacing(250);
        peripheralStiffnessSlider.setMaximum(1000);
        peripheralStiffnessSlider.setMinorTickSpacing(50);
        peripheralStiffnessSlider.setPaintLabels(true);
        peripheralStiffnessSlider.setPaintTicks(true);
        peripheralStiffnessSlider.setValue(0);
        peripheralStiffnessSlider.setPreferredSize(new java.awt.Dimension(190, 38));
        peripheralStiffnessSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                peripheralStiffnessSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 18;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        forcesPanel.add(peripheralStiffnessSlider, gridBagConstraints);

        enforceRangeboxCheckbox.setSelected(true);
        enforceRangeboxCheckbox.setPreferredSize(new java.awt.Dimension(28, 17));
        enforceRangeboxCheckbox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                enforceRangeboxCheckboxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 22;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(enforceRangeboxCheckbox, gridBagConstraints);

        canvasSizeSlider.setMajorTickSpacing(10);
        canvasSizeSlider.setPaintLabels(true);
        canvasSizeSlider.setPaintTicks(true);
        canvasSizeSlider.setPreferredSize(new java.awt.Dimension(240, 38));
        canvasSizeSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                canvasSizeSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 20;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        forcesPanel.add(canvasSizeSlider, gridBagConstraints);

        jLabel11.setText("Canvas Size");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 19;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel11, gridBagConstraints);

        flowRangeboxSizeSlider.setMajorTickSpacing(10);
        flowRangeboxSizeSlider.setMaximum(50);
        flowRangeboxSizeSlider.setPaintLabels(true);
        flowRangeboxSizeSlider.setPaintTicks(true);
        flowRangeboxSizeSlider.setPreferredSize(new java.awt.Dimension(190, 38));
        flowRangeboxSizeSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                flowRangeboxSizeSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 22;
        gridBagConstraints.gridheight = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 5, 0);
        forcesPanel.add(flowRangeboxSizeSlider, gridBagConstraints);

        jLabel13.setText("Flow Rangebox Size");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 21;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel13, gridBagConstraints);

        viewCanvasToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/ClosedEyeball16x16 copy.gif"))); // NOI18N
        viewCanvasToggleButton.setToolTipText("View");
        viewCanvasToggleButton.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        viewCanvasToggleButton.setDisabledIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/ClosedEyeball16x16 copy.gif"))); // NOI18N
        viewCanvasToggleButton.setFocusable(false);
        viewCanvasToggleButton.setSelectedIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Eyeball16x16.gif"))); // NOI18N
        viewCanvasToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                viewCanvasToggleButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 20;
        forcesPanel.add(viewCanvasToggleButton, gridBagConstraints);

        viewFlowRangeboxToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/ClosedEyeball16x16 copy.gif"))); // NOI18N
        viewFlowRangeboxToggleButton.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        viewFlowRangeboxToggleButton.setSelectedIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Eyeball16x16.gif"))); // NOI18N
        viewFlowRangeboxToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                viewFlowRangeboxToggleButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 23;
        forcesPanel.add(viewFlowRangeboxToggleButton, gridBagConstraints);

        angularDistributionSlider.setMajorTickSpacing(10);
        angularDistributionSlider.setMinorTickSpacing(5);
        angularDistributionSlider.setPaintLabels(true);
        angularDistributionSlider.setPaintTicks(true);
        angularDistributionSlider.setValue(100);
        angularDistributionSlider.setPreferredSize(new java.awt.Dimension(190, 38));
        angularDistributionSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                angularDistributionSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 26;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        forcesPanel.add(angularDistributionSlider, gridBagConstraints);

        jLabel28.setText("Angular Distribution");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 25;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        forcesPanel.add(jLabel28, gridBagConstraints);

        limitNodesRepulsionToBandCheckBox.setText("Limit Nodes Repulsion to Vertical Band");
        limitNodesRepulsionToBandCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                limitNodesRepulsionToBandCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 12;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 10, 0);
        forcesPanel.add(limitNodesRepulsionToBandCheckBox, gridBagConstraints);

        minPxDistanceOfFlowsFromNodesSlider.setMajorTickSpacing(2);
        minPxDistanceOfFlowsFromNodesSlider.setMaximum(20);
        minPxDistanceOfFlowsFromNodesSlider.setMinorTickSpacing(1);
        minPxDistanceOfFlowsFromNodesSlider.setPaintLabels(true);
        minPxDistanceOfFlowsFromNodesSlider.setPaintTicks(true);
        minPxDistanceOfFlowsFromNodesSlider.setSnapToTicks(true);
        minPxDistanceOfFlowsFromNodesSlider.setValue(10);
        minPxDistanceOfFlowsFromNodesSlider.setPreferredSize(new java.awt.Dimension(190, 40));
        minPxDistanceOfFlowsFromNodesSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                minPxDistanceOfFlowsFromNodesSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 28;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        forcesPanel.add(minPxDistanceOfFlowsFromNodesSlider, gridBagConstraints);

        minPxDistanceOfFlowsFromNodesSliderLabel.setText("Min. Pixel Distance of Flows from Nodes");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 27;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(5, 0, 0, 0);
        forcesPanel.add(minPxDistanceOfFlowsFromNodesSliderLabel, gridBagConstraints);

        controlsTabbedPane.addTab("Layout", forcesPanel);

        mapControlPanel.setLayout(new java.awt.GridBagLayout());

        jLabel9.setText("Map Layers");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 13;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        mapControlPanel.add(jLabel9, gridBagConstraints);

        flowDistanceFromEndPointFormattedTextField.setMinimumSize(new java.awt.Dimension(40, 30));
        flowDistanceFromEndPointFormattedTextField.setPreferredSize(new java.awt.Dimension(4, 30));
        flowDistanceFromEndPointFormattedTextField.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                flowDistanceFromEndPointFormattedTextFieldPropertyChange(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 3, 0);
        mapControlPanel.add(flowDistanceFromEndPointFormattedTextField, gridBagConstraints);

        jLabel29.setText("Flow Distance From Start Point");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        mapControlPanel.add(jLabel29, gridBagConstraints);

        flowDistanceFromStartPointFormattedTextField.setToolTipText("");
        flowDistanceFromStartPointFormattedTextField.setMinimumSize(new java.awt.Dimension(40, 30));
        flowDistanceFromStartPointFormattedTextField.setName(""); // NOI18N
        flowDistanceFromStartPointFormattedTextField.setPreferredSize(new java.awt.Dimension(4, 30));
        flowDistanceFromStartPointFormattedTextField.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                flowDistanceFromStartPointFormattedTextFieldPropertyChange(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 3, 0);
        mapControlPanel.add(flowDistanceFromStartPointFormattedTextField, gridBagConstraints);

        jLabel14.setText("Flow Distance From End Point");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        mapControlPanel.add(jLabel14, gridBagConstraints);

        layerListScrollPane.setPreferredSize(new java.awt.Dimension(220, 80));

        layerList.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
            public void valueChanged(javax.swing.event.ListSelectionEvent evt) {
                layerListValueChanged(evt);
            }
        });
        layerListScrollPane.setViewportView(layerList);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 14;
        gridBagConstraints.gridwidth = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        mapControlPanel.add(layerListScrollPane, gridBagConstraints);

        symbolPanel.setLayout(new java.awt.GridBagLayout());

        fillCheckBox.setText("Fill");
        fillCheckBox.setEnabled(false);
        fillCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fillCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        symbolPanel.add(fillCheckBox, gridBagConstraints);

        strokeCheckBox.setText("Stroke");
        strokeCheckBox.setEnabled(false);
        strokeCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                strokeCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        symbolPanel.add(strokeCheckBox, gridBagConstraints);

        fillColorButton.setEnabled(false);
        fillColorButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                fillColorButtonActionPerformed(evt);
            }
        });
        symbolPanel.add(fillColorButton, new java.awt.GridBagConstraints());

        strokeColorButton.setEnabled(false);
        strokeColorButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                strokeColorButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        symbolPanel.add(strokeColorButton, gridBagConstraints);

        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 15;
        gridBagConstraints.gridwidth = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        mapControlPanel.add(symbolPanel, gridBagConstraints);

        addLayerButton.setText("+");
        addLayerButton.setPreferredSize(new java.awt.Dimension(22, 22));
        addLayerButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addLayerButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 13;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_END;
        gridBagConstraints.weightx = 0.1;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 0, 3);
        mapControlPanel.add(addLayerButton, gridBagConstraints);

        jButton1.setText("-");
        jButton1.setPreferredSize(new java.awt.Dimension(22, 22));
        jButton1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jButton1ActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 13;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_END;
        mapControlPanel.add(jButton1, gridBagConstraints);

        maximumFlowWidthSlider.setMajorTickSpacing(20);
        maximumFlowWidthSlider.setMinorTickSpacing(10);
        maximumFlowWidthSlider.setPaintLabels(true);
        maximumFlowWidthSlider.setPaintTicks(true);
        maximumFlowWidthSlider.setPreferredSize(new java.awt.Dimension(220, 37));
        maximumFlowWidthSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                maximumFlowWidthSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 3, 0);
        mapControlPanel.add(maximumFlowWidthSlider, gridBagConstraints);

        jLabel26.setText("Maximum Flow Width");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        gridBagConstraints.gridwidth = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        mapControlPanel.add(jLabel26, gridBagConstraints);

        maximumNodeSizeSlider.setMajorTickSpacing(20);
        maximumNodeSizeSlider.setMinorTickSpacing(10);
        maximumNodeSizeSlider.setPaintLabels(true);
        maximumNodeSizeSlider.setPaintTicks(true);
        maximumNodeSizeSlider.setPreferredSize(new java.awt.Dimension(220, 37));
        maximumNodeSizeSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                maximumNodeSizeSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 3, 0);
        mapControlPanel.add(maximumNodeSizeSlider, gridBagConstraints);

        jLabel27.setText("Maximum Node Radius");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        mapControlPanel.add(jLabel27, gridBagConstraints);

        showNodesToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/ClosedEyeball16x16 copy.gif"))); // NOI18N
        showNodesToggleButton.setSelected(true);
        showNodesToggleButton.setBorderPainted(false);
        showNodesToggleButton.setPreferredSize(new java.awt.Dimension(20, 20));
        showNodesToggleButton.setSelectedIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Eyeball16x16.gif"))); // NOI18N
        showNodesToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showNodesToggleButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 20, 0);
        mapControlPanel.add(showNodesToggleButton, gridBagConstraints);

        showFlowsToggleButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/ClosedEyeball16x16 copy.gif"))); // NOI18N
        showFlowsToggleButton.setSelected(true);
        showFlowsToggleButton.setBorderPainted(false);
        showFlowsToggleButton.setPreferredSize(new java.awt.Dimension(20, 20));
        showFlowsToggleButton.setSelectedIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Eyeball16x16.gif"))); // NOI18N
        showFlowsToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showFlowsToggleButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 2;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 20, 0);
        mapControlPanel.add(showFlowsToggleButton, gridBagConstraints);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 11;
        gridBagConstraints.gridwidth = 3;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(15, 0, 15, 0);
        mapControlPanel.add(jSeparator9, gridBagConstraints);

        lockFeatureScaleToggleButton.setText("Lock Feature Scale");
        lockFeatureScaleToggleButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                lockFeatureScaleToggleButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 8;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.insets = new java.awt.Insets(8, 0, 0, 0);
        mapControlPanel.add(lockFeatureScaleToggleButton, gridBagConstraints);

        mapPanel.add(mapControlPanel);

        controlsTabbedPane.addTab("Map", mapPanel);

        arrowHeadsControlPanel.setLayout(new java.awt.GridBagLayout());

        addArrowsCheckbox.setText("Add Arrows");
        addArrowsCheckbox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                addArrowsCheckboxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(0, 0, 3, 0);
        arrowHeadsControlPanel.add(addArrowsCheckbox, gridBagConstraints);

        arrowheadLengthSlider.setMajorTickSpacing(200);
        arrowheadLengthSlider.setMaximum(800);
        arrowheadLengthSlider.setPaintLabels(true);
        arrowheadLengthSlider.setPaintTicks(true);
        arrowheadLengthSlider.setPreferredSize(new java.awt.Dimension(240, 43));
        {
            java.util.Hashtable labels = arrowheadLengthSlider.createStandardLabels(arrowheadLengthSlider.getMajorTickSpacing());
            java.util.Enumeration e = labels.elements();
            while(e.hasMoreElements()) {
                javax.swing.JComponent comp = (javax.swing.JComponent)e.nextElement();
                if (comp instanceof javax.swing.JLabel) {
                    javax.swing.JLabel label = (javax.swing.JLabel)(comp);
                    int i = Integer.parseInt(label.getText());
                    int p = (int)(i/4);
                    label.setText(Integer.toString(p));
                }
            }
            arrowheadLengthSlider.setLabelTable(labels);
        }
        arrowheadLengthSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                arrowheadLengthSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        arrowHeadsControlPanel.add(arrowheadLengthSlider, gridBagConstraints);

        jLabel10.setText("Arrowhead Length");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        arrowHeadsControlPanel.add(jLabel10, gridBagConstraints);

        arrowheadWidthSlider.setMajorTickSpacing(100);
        arrowheadWidthSlider.setMaximum(400);
        arrowheadWidthSlider.setPaintLabels(true);
        arrowheadWidthSlider.setPaintTicks(true);
        arrowheadWidthSlider.setPreferredSize(new java.awt.Dimension(240, 43));
        {
            java.util.Hashtable labels = arrowheadWidthSlider.createStandardLabels(arrowheadWidthSlider.getMajorTickSpacing());
            java.util.Enumeration e = labels.elements();
            while(e.hasMoreElements()) {
                javax.swing.JComponent comp = (javax.swing.JComponent)e.nextElement();
                if (comp instanceof javax.swing.JLabel) {
                    javax.swing.JLabel label = (javax.swing.JLabel)(comp);
                    int i = Integer.parseInt(label.getText());
                    int p = (int)(i/4);
                    label.setText(Integer.toString(p));
                }
            }
            arrowheadWidthSlider.setLabelTable(labels);
        }
        arrowheadWidthSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                arrowheadWidthSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 4;
        arrowHeadsControlPanel.add(arrowheadWidthSlider, gridBagConstraints);

        jLabel15.setText("Arrowhead Width");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(6, 0, 0, 0);
        arrowHeadsControlPanel.add(jLabel15, gridBagConstraints);

        jLabel16.setText("Arrow Edge Ctrl Point Length");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        arrowHeadsControlPanel.add(jLabel16, gridBagConstraints);

        jLabel17.setText("Arrow Edge Ctrl Point Width");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        arrowHeadsControlPanel.add(jLabel17, gridBagConstraints);

        arrowEdgeCtrlLengthSlider.setMajorTickSpacing(25);
        arrowEdgeCtrlLengthSlider.setPaintLabels(true);
        arrowEdgeCtrlLengthSlider.setPaintTicks(true);
        arrowEdgeCtrlLengthSlider.setPreferredSize(new java.awt.Dimension(240, 43));
        arrowEdgeCtrlLengthSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                arrowEdgeCtrlLengthSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        arrowHeadsControlPanel.add(arrowEdgeCtrlLengthSlider, gridBagConstraints);

        arrowEdgeCtrlWidthSlider.setMajorTickSpacing(50);
        arrowEdgeCtrlWidthSlider.setMaximum(200);
        arrowEdgeCtrlWidthSlider.setPaintLabels(true);
        arrowEdgeCtrlWidthSlider.setPaintTicks(true);
        arrowEdgeCtrlWidthSlider.setPreferredSize(new java.awt.Dimension(240, 43));
        arrowEdgeCtrlWidthSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                arrowEdgeCtrlWidthSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 8;
        arrowHeadsControlPanel.add(arrowEdgeCtrlWidthSlider, gridBagConstraints);

        arrowCornerPositionSlider.setMajorTickSpacing(25);
        arrowCornerPositionSlider.setMaximum(50);
        arrowCornerPositionSlider.setMinimum(-50);
        arrowCornerPositionSlider.setPaintLabels(true);
        arrowCornerPositionSlider.setPaintTicks(true);
        arrowCornerPositionSlider.setValue(0);
        arrowCornerPositionSlider.setPreferredSize(new java.awt.Dimension(240, 43));
        arrowCornerPositionSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                arrowCornerPositionSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 10;
        arrowHeadsControlPanel.add(arrowCornerPositionSlider, gridBagConstraints);

        jLabel18.setText("Arrow Corner Position");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 9;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        arrowHeadsControlPanel.add(jLabel18, gridBagConstraints);

        jLabel19.setText("Arrow Size Ratio");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 11;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(3, 0, 0, 0);
        arrowHeadsControlPanel.add(jLabel19, gridBagConstraints);

        arrowSizeRatioSlider.setMajorTickSpacing(10);
        arrowSizeRatioSlider.setPaintLabels(true);
        arrowSizeRatioSlider.setPaintTicks(true);
        arrowSizeRatioSlider.setValue(0);
        arrowSizeRatioSlider.setPreferredSize(new java.awt.Dimension(240, 43));
        arrowSizeRatioSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                arrowSizeRatioSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 12;
        arrowHeadsControlPanel.add(arrowSizeRatioSlider, gridBagConstraints);

        jLabel30.setText("Arrow Length Ratio");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 13;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        arrowHeadsControlPanel.add(jLabel30, gridBagConstraints);

        arrowLengthRatioSlider.setMajorTickSpacing(10);
        arrowLengthRatioSlider.setPaintLabels(true);
        arrowLengthRatioSlider.setPaintTicks(true);
        arrowLengthRatioSlider.setPreferredSize(new java.awt.Dimension(190, 43));
        arrowLengthRatioSlider.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                arrowLengthRatioSliderStateChanged(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 14;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        arrowHeadsControlPanel.add(arrowLengthRatioSlider, gridBagConstraints);

        useInFlowCheckbox.setText("Point Arrow Towards Endpoint (temp)");
        useInFlowCheckbox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                useInFlowCheckboxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 15;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        arrowHeadsControlPanel.add(useInFlowCheckbox, gridBagConstraints);

        drawInlineArrowsCheckBox.setText("Draw Inline Arrows");
        drawInlineArrowsCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                drawInlineArrowsCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 19;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(30, 0, 0, 0);
        arrowHeadsControlPanel.add(drawInlineArrowsCheckBox, gridBagConstraints);

        arrowHeadsPanel.add(arrowHeadsControlPanel);

        controlsTabbedPane.addTab("Arrows", arrowHeadsPanel);

        clipAreaControlPanel.setLayout(new java.awt.GridBagLayout());

        jLabel20.setText("Clipping Geometry");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 1;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(jLabel20, gridBagConstraints);

        selectEndClipAreaButton.setText("Select Shapefile�");
        selectEndClipAreaButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectEndClipAreaButtonActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 1;
        clipAreaControlPanel.add(selectEndClipAreaButton, gridBagConstraints);

        jLabel21.setText("Buffer Distance");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_END;
        gridBagConstraints.insets = new java.awt.Insets(0, 12, 0, 3);
        clipAreaControlPanel.add(jLabel21, gridBagConstraints);

        endAreasBufferDistanceFormattedTextField.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#,##0.######"))));
        endAreasBufferDistanceFormattedTextField.setToolTipText("Use the units of the flows and clipping area coordinates (typically degrees or meters).");
        endAreasBufferDistanceFormattedTextField.setValue(0.);
        endAreasBufferDistanceFormattedTextField.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                endAreasBufferDistanceFormattedTextFieldPropertyChange(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 5;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(endAreasBufferDistanceFormattedTextField, gridBagConstraints);

        clipWithEndAreasCheckBox.setText("Clip Ends");
        clipWithEndAreasCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clipWithEndAreasCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 4;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(clipWithEndAreasCheckBox, gridBagConstraints);

        drawEndClipAreasCheckBox.setText("Draw Buffered Areas");
        drawEndClipAreasCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                drawEndClipAreasCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 7;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(drawEndClipAreasCheckBox, gridBagConstraints);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 12;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(12, 0, 12, 0);
        clipAreaControlPanel.add(jSeparator5, gridBagConstraints);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 2;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.insets = new java.awt.Insets(12, 0, 12, 0);
        clipAreaControlPanel.add(jSeparator6, gridBagConstraints);

        jLabel24.setText("Buffer Distance");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 15;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_END;
        gridBagConstraints.insets = new java.awt.Insets(0, 12, 0, 3);
        clipAreaControlPanel.add(jLabel24, gridBagConstraints);

        startAreasBufferDistanceFormattedTextField.setFormatterFactory(new javax.swing.text.DefaultFormatterFactory(new javax.swing.text.NumberFormatter(new java.text.DecimalFormat("#,##0.######"))));
        startAreasBufferDistanceFormattedTextField.setToolTipText("Use the units of the flows and clipping area coordinates (typically degrees or meters).");
        startAreasBufferDistanceFormattedTextField.setValue(0.);
        startAreasBufferDistanceFormattedTextField.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                startAreasBufferDistanceFormattedTextFieldPropertyChange(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 15;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(startAreasBufferDistanceFormattedTextField, gridBagConstraints);

        drawStartClipAreasCheckBox.setText("Draw Buffered Areas");
        drawStartClipAreasCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                drawStartClipAreasCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 18;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(drawStartClipAreasCheckBox, gridBagConstraints);

        clipWithStartAreasCheckBox.setText("Clip Beginnings");
        clipWithStartAreasCheckBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clipWithStartAreasCheckBoxActionPerformed(evt);
            }
        });
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 1;
        gridBagConstraints.gridy = 14;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(clipWithStartAreasCheckBox, gridBagConstraints);

        jTextArea1.setEditable(false);
        jTextArea1.setFont(jTextArea1.getFont().deriveFont(jTextArea1.getFont().getSize()-2f));
        jTextArea1.setLineWrap(true);
        jTextArea1.setRows(3);
        jTextArea1.setText("With a positive distance flows end inside their destination area. With a negative distance flows end outside their destination area.");
        jTextArea1.setWrapStyleWord(true);
        jTextArea1.setOpaque(false);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 6;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(0, 12, 0, 0);
        clipAreaControlPanel.add(jTextArea1, gridBagConstraints);

        jTextArea2.setEditable(false);
        jTextArea2.setColumns(20);
        jTextArea2.setFont(jTextArea2.getFont().deriveFont(jTextArea2.getFont().getSize()-2f));
        jTextArea2.setLineWrap(true);
        jTextArea2.setRows(3);
        jTextArea2.setText("With a positive distance flows start inside their origin area. With a negative distance flows start outside their origin area.");
        jTextArea2.setWrapStyleWord(true);
        jTextArea2.setOpaque(false);
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 17;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        gridBagConstraints.insets = new java.awt.Insets(0, 12, 0, 0);
        clipAreaControlPanel.add(jTextArea2, gridBagConstraints);

        jLabel1.setText("Clip End of Flows");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 3;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(jLabel1, gridBagConstraints);

        jLabel2.setText("Clip Beginning of Flows");
        gridBagConstraints = new java.awt.GridBagConstraints();
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 13;
        gridBagConstraints.gridwidth = 2;
        gridBagConstraints.anchor = java.awt.GridBagConstraints.LINE_START;
        clipAreaControlPanel.add(jLabel2, gridBagConstraints);

        clipAreaPanel.add(clipAreaControlPanel);

        controlsTabbedPane.addTab("Clipping", clipAreaPanel);

        rightPanel.add(controlsTabbedPane, java.awt.BorderLayout.NORTH);

        progressBarPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 5, 10));
        progressBarPanel.setLayout(new javax.swing.BoxLayout(progressBarPanel, javax.swing.BoxLayout.LINE_AXIS));
        progressBarPanel.add(progressBar);

        rightPanel.add(progressBarPanel, java.awt.BorderLayout.SOUTH);

        getContentPane().add(rightPanel, java.awt.BorderLayout.EAST);

        fileMenu.setText("File");

        importFlowsMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        importFlowsMenuItem.setText("Open Flows�");
        importFlowsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importFlowsMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(importFlowsMenuItem);

        openPointsAndFlowsMenuItem.setText("Open Nodes and Flows�");
        openPointsAndFlowsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openPointsAndFlowsMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(openPointsAndFlowsMenuItem);
        fileMenu.add(jSeparator14);

        openSettingsMenuItem.setText("Open Settings�");
        openSettingsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openSettingsMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(openSettingsMenuItem);

        saveSettingsMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        saveSettingsMenuItem.setText("Save Settings�");
        saveSettingsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveSettingsMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(saveSettingsMenuItem);
        fileMenu.add(jSeparator3);

        exportSVGMenuItem.setText("Export SVG�");
        exportSVGMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportSVGMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(exportSVGMenuItem);

        exportImageMenuItem.setText("Export Image�");
        exportImageMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportImageMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(exportImageMenuItem);
        fileMenu.add(jSeparator4);

        exportFlowsCSVMenuItem.setText("Export Flows to CSV...");
        exportFlowsCSVMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportFlowsCSVMenuItemActionPerformed(evt);
            }
        });
        fileMenu.add(exportFlowsCSVMenuItem);

        menuBar.add(fileMenu);

        editMenu.setText("Edit");
        editMenu.addMenuListener(new javax.swing.event.MenuListener() {
            public void menuSelected(javax.swing.event.MenuEvent evt) {
                editMenuMenuSelected(evt);
            }
            public void menuDeselected(javax.swing.event.MenuEvent evt) {
            }
            public void menuCanceled(javax.swing.event.MenuEvent evt) {
            }
        });

        undoMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        undoMenuItem.setText("Undo");
        undoMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                undoMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(undoMenuItem);

        redoMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        redoMenuItem.setText("Redo");
        redoMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                redoMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(redoMenuItem);
        editMenu.add(jSeparator8);

        deleteMenuItem.setText("Delete");
        deleteMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deleteMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(deleteMenuItem);

        selectAllMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        selectAllMenuItem.setText("Select All");
        selectAllMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectAllMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(selectAllMenuItem);

        selectNoneMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        selectNoneMenuItem.setText("Deselect All");
        selectNoneMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectNoneMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(selectNoneMenuItem);
        editMenu.add(jSeparator10);

        lockMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_L, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        lockMenuItem.setText("Lock");
        lockMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                lockMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(lockMenuItem);

        unlockMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_L, java.awt.event.InputEvent.SHIFT_MASK | java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        unlockMenuItem.setText("Unlock");
        unlockMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                unlockMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(unlockMenuItem);
        editMenu.add(jSeparator11);

        reverseFlowDirectionMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        reverseFlowDirectionMenuItem.setText("Reverse Flow Direction");
        reverseFlowDirectionMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                reverseFlowDirectionMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(reverseFlowDirectionMenuItem);

        straightenFlowsMenuItem.setText("Straigthen Flows");
        straightenFlowsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                straightenFlowsMenuItemActionPerformed(evt);
            }
        });
        editMenu.add(straightenFlowsMenuItem);

        menuBar.add(editMenu);

        mapMenu.setText("Layers");

        openShapefileMenuItem.setText("Add Layer from Shapefile�");
        openShapefileMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                openShapefileMenuItemActionPerformed(evt);
            }
        });
        mapMenu.add(openShapefileMenuItem);
        mapMenu.add(jSeparator1);

        removeAllLayersMenuItem.setText("Remove All Layers");
        removeAllLayersMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                removeAllLayersMenuItemActionPerformed(evt);
            }
        });
        mapMenu.add(removeAllLayersMenuItem);

        removeSelectedLayerMenuItem.setText("Remove Selected Layer");
        removeSelectedLayerMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                removeSelectedLayerMenuItemActionPerformed(evt);
            }
        });
        mapMenu.add(removeSelectedLayerMenuItem);

        menuBar.add(mapMenu);

        viewMenu.setText("View");

        showAllMenuItem.setText("Show All");
        showAllMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showAllMenuItemActionPerformed(evt);
            }
        });
        viewMenu.add(showAllMenuItem);

        showAllMenuItem1.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_0, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        showAllMenuItem1.setText("Zoom on Flows");
        showAllMenuItem1.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showAllMenuItem1ActionPerformed(evt);
            }
        });
        viewMenu.add(showAllMenuItem1);

        zoomOnSelectedLayerMenuItem.setText("Zoom on Selected Layer");
        zoomOnSelectedLayerMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                zoomOnSelectedLayerMenuItemActionPerformed(evt);
            }
        });
        viewMenu.add(zoomOnSelectedLayerMenuItem);
        viewMenu.add(viewSeparator);

        viewZoomInMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_PLUS,    java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        viewZoomInMenuItem.setText("Zoom In");
        viewZoomInMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                viewZoomInMenuItemActionPerformed(evt);
            }
        });
        viewMenu.add(viewZoomInMenuItem);

        viewZoomOutMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS,    java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        viewZoomOutMenuItem.setText("Zoom Out");
        viewZoomOutMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                viewZoomOutMenuItemActionPerformed(evt);
            }
        });
        viewMenu.add(viewZoomOutMenuItem);

        menuBar.add(viewMenu);

        infoMenu.setText("Info");

        floxReportMenuItem.setText("Report�");
        floxReportMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                floxReportMenuItemActionPerformed(evt);
            }
        });
        infoMenu.add(floxReportMenuItem);
        infoMenu.add(jSeparator2);

        infoMenuItem.setText("Info�");
        infoMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                infoMenuItemActionPerformed(evt);
            }
        });
        infoMenu.add(infoMenuItem);

        menuBar.add(infoMenu);

        jMenu1.setText("Debug");

        flowSegmentationMenu.setText("Flow Segmentation");

        buttonGroup1.add(lowFlowSegmentationMenuItem);
        lowFlowSegmentationMenuItem.setSelected(true);
        lowFlowSegmentationMenuItem.setText("Low");
        lowFlowSegmentationMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                lowFlowSegmentationMenuItemActionPerformed(evt);
            }
        });
        flowSegmentationMenu.add(lowFlowSegmentationMenuItem);

        buttonGroup1.add(mediumFlowSegmentationMenuItem);
        mediumFlowSegmentationMenuItem.setText("Medium");
        mediumFlowSegmentationMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mediumFlowSegmentationMenuItemActionPerformed(evt);
            }
        });
        flowSegmentationMenu.add(mediumFlowSegmentationMenuItem);

        buttonGroup1.add(highFlowSegmentationMenuItem);
        highFlowSegmentationMenuItem.setText("High");
        highFlowSegmentationMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                highFlowSegmentationMenuItemActionPerformed(evt);
            }
        });
        flowSegmentationMenu.add(highFlowSegmentationMenuItem);

        jMenu1.add(flowSegmentationMenu);

        showFlowSegmentsMenuItem.setText("Show Flow Segments");
        showFlowSegmentsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                showFlowSegmentsMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(showFlowSegmentsMenuItem);

        enforceCanvasCheckBoxMenuItem.setSelected(true);
        enforceCanvasCheckBoxMenuItem.setText("Enforce Canvas");
        enforceCanvasCheckBoxMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                enforceCanvasCheckBoxMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(enforceCanvasCheckBoxMenuItem);

        useFrictionCheckBoxMenuItem.setText("Use Friction for Forces");
        useFrictionCheckBoxMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                useFrictionCheckBoxMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(useFrictionCheckBoxMenuItem);

        useAngularFrictionCheckBoxMenuItem.setText("Use Friction for Angular Distribution");
        useAngularFrictionCheckBoxMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                useAngularFrictionCheckBoxMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(useAngularFrictionCheckBoxMenuItem);

        moveFlowsCheckBoxMenuItem.setSelected(true);
        moveFlowsCheckBoxMenuItem.setText("Move Flows Overlapping Nodes");
        moveFlowsCheckBoxMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                moveFlowsCheckBoxMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(moveFlowsCheckBoxMenuItem);
        jMenu1.add(jSeparator13);

        emptySpaceMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        emptySpaceMenuItem.setText("Attract First Selected Flow by Empty Space");
        emptySpaceMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                emptySpaceMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(emptySpaceMenuItem);
        jMenu1.add(jSeparator7);

        recomputeMenuItem.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_K, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
        recomputeMenuItem.setText("Recompute");
        recomputeMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                recomputeMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(recomputeMenuItem);

        liveDrawingCheckBoxMenuItem.setText("Live Drawing");
        liveDrawingCheckBoxMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                liveDrawingCheckBoxMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(liveDrawingCheckBoxMenuItem);

        menuBar.add(jMenu1);

        setJMenuBar(menuBar);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void exportSVGMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportSVGMenuItemActionPerformed

        OutputStream outputStream = null;
        try {
            // ask for export file
            String name = getTitle() + ".svg";
            String outFilePath = FileUtils.askFile(this, "SVG File", name, false, "svg");
            if (outFilePath == null) {
                // user canceled
                return;
            }
            SVGFlowExporter exporter = new SVGFlowExporter(model, mapComponent);
            exporter.setSVGCanvasSize(mapComponent.getWidth(), mapComponent.getHeight());
            outputStream = new FileOutputStream(outFilePath);
            exporter.export(outputStream);
        } catch (Throwable ex) {
            showErrorDialog("Could not export to a SVG file.", ex);
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
            } catch (IOException ex) {
                Logger.getLogger(MainWindow.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

    }//GEN-LAST:event_exportSVGMenuItemActionPerformed

    private void addLayer(GeometryCollection geometry, String name) {
        Layer layer = model.addLayer(geometry);
        layer.setName(name);
        updateLayerList();
        layerList.setSelectedIndex(0);
        mapComponent.showAll();
    }

    /**
     * Open an Esri shapefile
     */
    public void openShapefile() {
        try {
            // ask for import file
            String inFilePath = FileUtils.askFile(this, "Shapefile", true);
            if (inFilePath == null) {
                // user canceled
                return;
            }

            // read shapefile
            GeometryCollection collection = new ShapeGeometryImporter().read(inFilePath);
            if (collection == null) {
                showErrorDialog("The selected file is not a shapefile.", null);
                return;
            }
            addLayer(collection, FileUtils.getFileNameWithoutExtension(inFilePath));
        } catch (Throwable ex) {
            showErrorDialog("Could not open the Shapefile.", ex);
        } finally {
            writeSymbolGUI();
        }
    }

    /**
     * Passes flows to the model and initializes the GUI for the flows.
     *
     * @param flows
     * @param filePath
     */
    private void setFlows(ArrayList<Flow> flows, String filePath) {
        if (flows != null) {
            setTitle(FileUtils.getFileNameWithoutExtension(filePath));
            model.setFlows(flows);
            flowDistanceFromEndPointFormattedTextField.setValue(model.getFlowDistanceFromEndPointPixel());
            flowDistanceFromStartPointFormattedTextField.setValue(model.getFlowDistanceFromStartPointPixel());
            layout("Load Flows");
            mapComponent.showAll();
        }
    }

    /**
     * Open a CSV file with flows
     */
    public void openFlowsCSVFile() {
        try {
            // ask for import file
            String inFilePath = FileUtils.askFile(this, "CSV Flows File", true);
            if (inFilePath == null) {
                // user canceled
                return;
            }
            ArrayList<Flow> flows = FlowImporter.readFlows(inFilePath);
            setFlows(flows, inFilePath);
            sizeFeaturesToScale();

            // the user might have loaded clipping areas before. Apply these
            // clipping area to the new flows.
            applyClippingSettings();
        } catch (Throwable ex) {
            showErrorDialog("The file could not be read.", ex);
        }
    }

    /**
     * Returns the layer currently selected by the user.
     *
     * @return The selected map layer or null if none is selected.
     */
    private Layer getSelectedMapLayer() {
        assert SwingUtilities.isEventDispatchThread();
        int index = layerList.getSelectedIndex();
        return index == -1 ? null : model.getLayer(index);
    }

    private VectorSymbol getSelectedVectorSymbol() {
        Layer selectedLayer = getSelectedMapLayer();
        VectorSymbol vectorSymbol = null;
        if (selectedLayer != null) {
            vectorSymbol = selectedLayer.getVectorSymbol();
        }
        return vectorSymbol;
    }

    private void writeSymbolGUI() {
        VectorSymbol vectorSymbol = getSelectedVectorSymbol();

        boolean enable = vectorSymbol != null;
        fillCheckBox.setEnabled(enable);
        strokeCheckBox.setEnabled(enable);
        fillColorButton.setEnabled(enable);
        strokeColorButton.setEnabled(enable);

        if (vectorSymbol != null) {
            fillCheckBox.setSelected(vectorSymbol.isFilled());
            strokeCheckBox.setSelected(vectorSymbol.isStroked());
            fillColorButton.setColor(vectorSymbol.getFillColor());
            strokeColorButton.setColor(vectorSymbol.getStrokeColor());
        }
    }

    private void readSymbolGUI() {
        VectorSymbol vectorSymbol = getSelectedVectorSymbol();
        if (vectorSymbol == null) {
            return;
        }
        vectorSymbol.setFilled(fillCheckBox.isSelected());
        vectorSymbol.setStroked(strokeCheckBox.isSelected());
        vectorSymbol.setFillColor(fillColorButton.getColor());
        vectorSymbol.setStrokeColor(strokeColorButton.getColor());
    }

    private void openShapefileMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openShapefileMenuItemActionPerformed
        openShapefile();
    }//GEN-LAST:event_openShapefileMenuItemActionPerformed

    private void removeAllLayersMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_removeAllLayersMenuItemActionPerformed
        model.removeAllLayers();
        mapComponent.showAll();
        mapComponent.refreshMap();
        updateLayerList();
    }//GEN-LAST:event_removeAllLayersMenuItemActionPerformed

    private void layerListValueChanged(javax.swing.event.ListSelectionEvent evt) {//GEN-FIRST:event_layerListValueChanged
        if (!evt.getValueIsAdjusting()) {
            writeSymbolGUI();
        }
    }//GEN-LAST:event_layerListValueChanged

    private void fillCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fillCheckBoxActionPerformed
        readSymbolGUI();
        mapComponent.refreshMap();
        addUndo("Add/Remove Fill");
    }//GEN-LAST:event_fillCheckBoxActionPerformed

    private void strokeCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_strokeCheckBoxActionPerformed
        readSymbolGUI();
        mapComponent.refreshMap();
    }//GEN-LAST:event_strokeCheckBoxActionPerformed

    private void fillColorButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_fillColorButtonActionPerformed
        if (!fillCheckBox.isSelected()) {
            fillCheckBox.setSelected(true);
        }
        readSymbolGUI();
        mapComponent.repaint();
    }//GEN-LAST:event_fillColorButtonActionPerformed

    private void strokeColorButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_strokeColorButtonActionPerformed
        if (!strokeCheckBox.isSelected()) {
            strokeCheckBox.setSelected(true);
        }
        readSymbolGUI();
        mapComponent.repaint();
    }//GEN-LAST:event_strokeColorButtonActionPerformed

    private void removeSelectedLayer() {
        int selectedLayerID = layerList.getSelectedIndex();
        if (selectedLayerID < 0) {
            return;
        }
        model.removeLayer(selectedLayerID);
        updateLayerList();
        layerList.setSelectedIndex(--selectedLayerID);
        writeSymbolGUI();
        mapComponent.refreshMap();
    }

    private void removeSelectedLayerMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_removeSelectedLayerMenuItemActionPerformed
        removeSelectedLayer();
    }//GEN-LAST:event_removeSelectedLayerMenuItemActionPerformed

    private void importFlowsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importFlowsMenuItemActionPerformed
        openFlowsCSVFile();
    }//GEN-LAST:event_importFlowsMenuItemActionPerformed

    private void showAllMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showAllMenuItemActionPerformed
        mapComponent.showAll();
    }//GEN-LAST:event_showAllMenuItemActionPerformed

    private void viewZoomInMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_viewZoomInMenuItemActionPerformed
        mapComponent.zoomIn();
    }//GEN-LAST:event_viewZoomInMenuItemActionPerformed

    private void viewZoomOutMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_viewZoomOutMenuItemActionPerformed
        mapComponent.zoomOut();
    }//GEN-LAST:event_viewZoomOutMenuItemActionPerformed

    private void zoomOnSelectedLayerMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_zoomOnSelectedLayerMenuItemActionPerformed
        Layer layer = getSelectedMapLayer();
        if (layer == null) {
            return;
        }
        Envelope bb = layer.getGeometry().getEnvelopeInternal();
        Rectangle2D.Double bbRect = new Rectangle2D.Double(bb.getMinX(), bb.getMinY(),
                bb.getWidth(), bb.getHeight());
        mapComponent.zoomOnRectangle(bbRect);
    }//GEN-LAST:event_zoomOnSelectedLayerMenuItemActionPerformed

    private void showReport() {
        int nbrIntersections = LayoutGrader.countFlowIntersections(model);
        int nbrFlows = model.getNbrFlows();
        int nbrNodes = model.getNbrNodes();

        StringBuilder sb = new StringBuilder();
        sb.append("Flows: ");
        sb.append(nbrFlows);
        sb.append("\nNodes: ");
        sb.append(nbrNodes);
        sb.append("\nIntersections: ");
        sb.append(nbrIntersections);
        JOptionPane.showMessageDialog(mapComponent, sb.toString(), "Flox", JOptionPane.INFORMATION_MESSAGE);
    }

    private void exponentSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_exponentSliderStateChanged
        if (exponentSlider.getValueIsAdjusting() == false) {
            int v = (int) Math.round(Math.pow(2, exponentSlider.getValue() - 1));
            model.setDistanceWeightExponent(v);
            layout("Exponent");
        }
    }//GEN-LAST:event_exponentSliderStateChanged

    private void longestFlowStiffnessSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_longestFlowStiffnessSliderStateChanged
        if (longestFlowStiffnessSlider.getValueIsAdjusting() == false && model != null) {
            model.setMaxFlowLengthSpringConstant(longestFlowStiffnessSlider.getValue() / 100d);
            layout("Flow Stiffness");
        }
    }//GEN-LAST:event_longestFlowStiffnessSliderStateChanged

    private void zeroLengthStiffnessSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_zeroLengthStiffnessSliderStateChanged
        if (zeroLengthStiffnessSlider.getValueIsAdjusting() == false && model != null) {
            model.setMinFlowLengthSpringConstant(zeroLengthStiffnessSlider.getValue() / 100d);
            layout("Zero Length Stiffness");
        }
    }//GEN-LAST:event_zeroLengthStiffnessSliderStateChanged

    private void nodeWeightSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_nodeWeightSliderStateChanged
        if (nodeWeightSlider.getValueIsAdjusting() == false) {
            model.setNodesWeight(nodeWeightSlider.getValue() / 10d);
            layout("Node Weight");
        }
    }//GEN-LAST:event_nodeWeightSliderStateChanged

    private void antiTorsionSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_antiTorsionSliderStateChanged
        if (antiTorsionSlider.getValueIsAdjusting() == false) {
            model.setAntiTorsionWeight(antiTorsionSlider.getValue() / 100d);
            layout("Anti-Torsion");
        }
    }//GEN-LAST:event_antiTorsionSliderStateChanged

    private void peripheralStiffnessSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_peripheralStiffnessSliderStateChanged
        if (peripheralStiffnessSlider.getValueIsAdjusting() == false) {
            model.setPeripheralStiffnessFactor(peripheralStiffnessSlider.getValue() / 100d);
            layout("Peripheral Stiffness");
        }
    }//GEN-LAST:event_peripheralStiffnessSliderStateChanged

    private void floxReportMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_floxReportMenuItemActionPerformed
        showReport();
    }//GEN-LAST:event_floxReportMenuItemActionPerformed

    private void enforceRangeboxCheckboxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_enforceRangeboxCheckboxActionPerformed
        if (model != null) {
            model.setEnforceRangebox(enforceRangeboxCheckbox.isSelected());
            layout("Enforce Range Box");
        }
        flowRangeboxSizeSlider.setEnabled(enforceRangeboxCheckbox.isSelected());

    }//GEN-LAST:event_enforceRangeboxCheckboxActionPerformed

    private void showAllMenuItem1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showAllMenuItem1ActionPerformed
        mapComponent.zoomOnRectangle(model.getFlowsBoundingBox());
    }//GEN-LAST:event_showAllMenuItem1ActionPerformed

    private void infoMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_infoMenuItemActionPerformed
        ProgramInfoPanel.showApplicationInfo(this);
    }//GEN-LAST:event_infoMenuItemActionPerformed

    private void exportImageMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportImageMenuItemActionPerformed
        try {
            int size = Math.max(mapComponent.getWidth(), mapComponent.getHeight());
            String msg = "Length of longer side in pixels.";
            String input = JOptionPane.showInputDialog(this, msg, size);
            if (input == null) {
                return;
            }
            try {
                size = Math.abs(Integer.parseInt(input));
            } catch (NumberFormatException ex) {
                showErrorDialog("Invalid image size.", ex);
                return;
            }
            if (size > 5000) {
                showErrorDialog("The entered size must be smaller than 5000.", null);
                return;
            }

            // Get the area of the map to be drawn to the image
            Rectangle2D bb = mapComponent.getVisibleArea();

            // ask user for file
            String name = getTitle() + ".png";
            String filePath = FileUtils.askFile(this, "PNG Image File", name, false, "png");
            if (filePath == null) {
                // user canceled
                return;
            }

            // render image
            BufferedImage image = FloxRenderer.renderToImage(model, size, bb,
                    true, // antialiasing
                    true, // draw background 
                    false, // fill node circles
                    true, // draw selected flows 
                    mapComponent.isDrawFlows(), // draw flows
                    mapComponent.isDrawNodes()); // draw nodes

            // write image to file
            ImageIO.write(image, "png", new File(filePath));
        } catch (Throwable ex) {
            showErrorDialog("Could not export the image.", ex);
        }
    }//GEN-LAST:event_exportImageMenuItemActionPerformed

    private void canvasSizeSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_canvasSizeSliderStateChanged

        model.setCanvasPadding(canvasSizeSlider.getValue() / 100d);

        if (mapComponent.isDrawCanvas()) {
            mapComponent.refreshMap();
        }

        if (canvasSizeSlider.getValueIsAdjusting() == false) {
            layout("Canvas Size");
        }
    }//GEN-LAST:event_canvasSizeSliderStateChanged

    private void flowRangeboxSizeSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_flowRangeboxSizeSliderStateChanged
        model.setFlowRangeboxHeight(flowRangeboxSizeSlider.getValue() / 100d + 0.01);
        mapComponent.refreshMap();
        if (flowRangeboxSizeSlider.getValueIsAdjusting() == false) {
            layout("Flow Rangebox Size");
        }
    }//GEN-LAST:event_flowRangeboxSizeSliderStateChanged

    private void arrowToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_arrowToggleButtonActionPerformed
        mapComponent.setMapTool(new ScaleMoveSelectionTool(mapComponent,
                valueFormattedTextField, xFormattedTextField, yFormattedTextField,
                lockUnlockButton));
    }//GEN-LAST:event_arrowToggleButtonActionPerformed

    private void zoomInToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_zoomInToggleButtonActionPerformed
        mapComponent.setMapTool(new ZoomInTool(mapComponent));
    }//GEN-LAST:event_zoomInToggleButtonActionPerformed

    private void zoomOutToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_zoomOutToggleButtonActionPerformed
        mapComponent.setMapTool(new ZoomOutTool(mapComponent));
    }//GEN-LAST:event_zoomOutToggleButtonActionPerformed

    private void handToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_handToggleButtonActionPerformed
        mapComponent.setMapTool(new PanTool(mapComponent));
    }//GEN-LAST:event_handToggleButtonActionPerformed

    private void distanceToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_distanceToggleButtonActionPerformed
        MeasureTool tool = new MeasureTool(this.mapComponent);
        tool.addMeasureToolListener(this.coordinateInfoPanel);
        this.mapComponent.setMapTool(tool);
    }//GEN-LAST:event_distanceToggleButtonActionPerformed

    private void showAllButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showAllButtonActionPerformed
        mapComponent.zoomOnRectangle(model.getFlowsBoundingBox());
    }//GEN-LAST:event_showAllButtonActionPerformed

    private void flowDistanceFromEndPointFormattedTextFieldPropertyChange(java.beans.PropertyChangeEvent evt) {//GEN-FIRST:event_flowDistanceFromEndPointFormattedTextFieldPropertyChange
        if ("value".equals(evt.getPropertyName()) && model != null) {
            double s = ((Number) flowDistanceFromEndPointFormattedTextField.getValue()).doubleValue();
            model.setFlowDistanceFromEndPointPixel(s);
            mapComponent.refreshMap();
        }
    }//GEN-LAST:event_flowDistanceFromEndPointFormattedTextFieldPropertyChange

    private void addArrowsCheckboxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addArrowsCheckboxActionPerformed
        if (model != null) {
            model.setAddArrows(addArrowsCheckbox.isSelected());
            mapComponent.refreshMap();
            addUndo("Add Arrows");
        }
    }//GEN-LAST:event_addArrowsCheckboxActionPerformed

    private void arrowheadLengthSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_arrowheadLengthSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setArrowLengthScaleFactor((arrowheadLengthSlider.getValue() + 1) / 40d);
            updateArrowHeads();
            mapComponent.refreshMap();
            if (!arrowheadLengthSlider.getValueIsAdjusting()) {
                addUndo("Arrow Length");
            }
        }
    }//GEN-LAST:event_arrowheadLengthSliderStateChanged

    private void arrowheadWidthSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_arrowheadWidthSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setArrowWidthScaleFactor((arrowheadWidthSlider.getValue() + 1) / 40d);
            updateArrowHeads();
            mapComponent.refreshMap();
            if (!arrowheadWidthSlider.getValueIsAdjusting()) {
                addUndo("Arrow Width");
            }
        }
    }//GEN-LAST:event_arrowheadWidthSliderStateChanged

    private void arrowEdgeCtrlLengthSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_arrowEdgeCtrlLengthSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setArrowEdgeCtrlLength((arrowEdgeCtrlLengthSlider.getValue()) / 100d);
            updateArrowHeads();
            mapComponent.refreshMap();
            if (!arrowEdgeCtrlLengthSlider.getValueIsAdjusting()) {
                addUndo("Arrow Edge Shape");
            }
        }
    }//GEN-LAST:event_arrowEdgeCtrlLengthSliderStateChanged

    private void arrowEdgeCtrlWidthSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_arrowEdgeCtrlWidthSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setArrowEdgeCtrlWidth((arrowEdgeCtrlWidthSlider.getValue()) / 100d);
            updateArrowHeads();
            mapComponent.refreshMap();
            if (!arrowEdgeCtrlWidthSlider.getValueIsAdjusting()) {
                addUndo("Arrow Edge Shape");
            }
        }
    }//GEN-LAST:event_arrowEdgeCtrlWidthSliderStateChanged

    private void arrowCornerPositionSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_arrowCornerPositionSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setArrowCornerPosition((arrowCornerPositionSlider.getValue()) / 100d);
            updateArrowHeads();
            mapComponent.refreshMap();
            if (!arrowCornerPositionSlider.getValueIsAdjusting()) {
                addUndo("Arrow Corner Position");
            }
        }
    }//GEN-LAST:event_arrowCornerPositionSliderStateChanged

    private void selectEndClipAreaButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectEndClipAreaButtonActionPerformed
        try {
            // ask for import file
            String inFilePath = FileUtils.askFile(this, "Shapefile", true);
            if (inFilePath == null) {
                // user canceled
                return;
            }

            // read shapefile
            GeometryCollection collection = new ShapeGeometryImporter().read(inFilePath);
            if (collection == null) {
                showErrorDialog("The selected file is not a shapefile.", null);
                return;
            }

            model.setClipAreas(collection);
            writeModelToGUI();
            clipWithEndAreasCheckBox.doClick();

            String fileName = FileUtils.getFileNameWithoutExtension(inFilePath);
            Layer layer = model.getLayer(fileName);
            if (layer == null) {
                String msg = "Do you want to add the clipping areas as a layer to the map?";
                String title = "Flox";
                Object[] options = {"Add as Layer", "No"};
                int res = JOptionPane.showOptionDialog(this, msg, title, JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
                if (res == 0) {
                    addLayer(collection, fileName);
                }
            }
        } catch (Throwable ex) {
            showErrorDialog("An error occured.", ex);
        } finally {
            writeSymbolGUI();
        }
    }//GEN-LAST:event_selectEndClipAreaButtonActionPerformed

    private void clipWithEndAreasCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clipWithEndAreasCheckBoxActionPerformed
        if (updatingGUI == false && model != null) {
            boolean clipEnds = clipWithEndAreasCheckBox.isSelected();
            model.setClipFlowEnds(clipEnds);
            if (clipEnds) {
                model.updateEndClipAreas();
            } else {
                model.removeEndClipAreasFromFlows();
            }
            layout("Clip with End Areas");
            mapComponent.refreshMap();
            writeModelToGUI();
        }
    }//GEN-LAST:event_clipWithEndAreasCheckBoxActionPerformed

    private void endAreasBufferDistanceFormattedTextFieldPropertyChange(java.beans.PropertyChangeEvent evt) {//GEN-FIRST:event_endAreasBufferDistanceFormattedTextFieldPropertyChange
        if (updatingGUI == false && model != null) {
            if ("value".equals(evt.getPropertyName())) {
                double d = ((Number) endAreasBufferDistanceFormattedTextField.getValue()).doubleValue();
                model.setEndClipAreaBufferDistance(d);
                layout("Buffered Distance");
                mapComponent.refreshMap();
            }
        }
    }//GEN-LAST:event_endAreasBufferDistanceFormattedTextFieldPropertyChange

    protected JOptionPane getOptionPane(JComponent parent) {
        JOptionPane pane;
        if (!(parent instanceof JOptionPane)) {
            pane = getOptionPane((JComponent) parent.getParent());
        } else {
            pane = (JOptionPane) parent;
        }
        return pane;
    }

    private void openPointsAndFlowsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openPointsAndFlowsMenuItemActionPerformed
        String title = "Open Nodes and Flows";
        importPanelOKButton.setEnabled(false);
        pointsFilePathLabel.setText("�");
        flowsFilePathLabel.setText("�");
        // http://stackoverflow.com/questions/14334931/disable-ok-button-on-joptionpane-dialog-until-user-gives-an-input/14335083#14335083
        int res = JOptionPane.showOptionDialog(
                this,
                importPanel,
                title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                new Object[]{importPanelOKButton, importPanelCancelButton},
                importPanelOKButton);
        if (res != 0) {
            return;
        }
        try {
            String pointsFilePath = pointsFilePathLabel.getText();
            String flowsFilePath = flowsFilePathLabel.getText();
            ArrayList<Flow> flows = FlowImporter.readFlows(pointsFilePath, flowsFilePath);
            setFlows(flows, flowsFilePath);
            sizeFeaturesToScale();
            // the user might have loaded clipping areas before. Apply these
            // clipping area to the new flows.
            applyClippingSettings();
        } catch (Throwable ex) {
            showErrorDialog("The flows could not be imported.", ex);
        }
    }//GEN-LAST:event_openPointsAndFlowsMenuItemActionPerformed

    private void applyClippingSettings() {
        if (clipWithStartAreasCheckBox.isSelected()) {
            model.updateStartClipAreas();
        }
        model.setClipFlowStarts(clipWithStartAreasCheckBox.isSelected());

        if (clipWithEndAreasCheckBox.isSelected()) {
            model.updateEndClipAreas();
        }
        model.setClipFlowEnds(clipWithEndAreasCheckBox.isSelected());

        writeModelToGUI();
    }

    private void sizeFeaturesToScale() {
        if (lockFeatureScaleToggleButton.isSelected()) {
            model.setLockedMapScale(mapComponent.getScale());
            mapComponent.refreshMap();
        } else {
            lockFeatureScaleToggleButton.doClick();
        }
    }

    private void selectPointsFileButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectPointsFileButtonActionPerformed
        String filePath = FileUtils.askFile(this, "Nodes File (CSV)", true);
        if (filePath == null) {
            // user canceled
            return;
        }
        // abusing JLabel to store user input
        pointsFilePathLabel.setText(filePath);
        importPanelOKButton.setEnabled(flowsFilePathLabel.getText().length() > 2);
    }//GEN-LAST:event_selectPointsFileButtonActionPerformed

    private void selectFlowsFileButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectFlowsFileButtonActionPerformed
        String filePath = FileUtils.askFile(this, "Flows File (CSV)", true);
        if (filePath == null) {
            // user canceled
            return;
        }
        // abusing JLabel to store user input
        flowsFilePathLabel.setText(filePath);
        importPanelOKButton.setEnabled(pointsFilePathLabel.getText().length() > 2);
    }//GEN-LAST:event_selectFlowsFileButtonActionPerformed

    private void importPanelOKButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importPanelOKButtonActionPerformed
        JOptionPane pane = getOptionPane((JComponent) evt.getSource());
        pane.setValue(importPanelOKButton);
    }//GEN-LAST:event_importPanelOKButtonActionPerformed

    private void importPanelCancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importPanelCancelButtonActionPerformed
        JOptionPane pane = getOptionPane((JComponent) evt.getSource());
        pane.setValue(importPanelCancelButton);
    }//GEN-LAST:event_importPanelCancelButtonActionPerformed

    private void drawEndClipAreasCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_drawEndClipAreasCheckBoxActionPerformed
        mapComponent.setDrawEndClipAreas(drawEndClipAreasCheckBox.isSelected());
        mapComponent.refreshMap();
    }//GEN-LAST:event_drawEndClipAreasCheckBoxActionPerformed

    private void startAreasBufferDistanceFormattedTextFieldPropertyChange(java.beans.PropertyChangeEvent evt) {//GEN-FIRST:event_startAreasBufferDistanceFormattedTextFieldPropertyChange
        if (updatingGUI == false && model != null) {
            if ("value".equals(evt.getPropertyName())) {
                double d = ((Number) startAreasBufferDistanceFormattedTextField.getValue()).doubleValue();
                model.setStartClipAreaBufferDistance(d);
                layout("Buffer Distance");
                mapComponent.refreshMap();
            }
        }
    }//GEN-LAST:event_startAreasBufferDistanceFormattedTextFieldPropertyChange

    private void drawStartClipAreasCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_drawStartClipAreasCheckBoxActionPerformed
        mapComponent.setDrawStartClipAreas(drawStartClipAreasCheckBox.isSelected());
        mapComponent.refreshMap();
    }//GEN-LAST:event_drawStartClipAreasCheckBoxActionPerformed

    private void clipWithStartAreasCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clipWithStartAreasCheckBoxActionPerformed
        if (updatingGUI == false && model != null) {
            boolean clip = clipWithStartAreasCheckBox.isSelected();
            model.setClipFlowStarts(clip);
            if (clip) {
                model.updateStartClipAreas();
            } else {
                model.removeStartClipAreasFromFlows();
            }
            layout("Clip with Start Areas");
            mapComponent.refreshMap();
            writeModelToGUI();
        }
    }//GEN-LAST:event_clipWithStartAreasCheckBoxActionPerformed

    private void arrowSizeRatioSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_arrowSizeRatioSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setArrowSizeRatio((arrowSizeRatioSlider.getValue()) / 100d);
            updateArrowHeads();
            mapComponent.refreshMap();
            if (!arrowSizeRatioSlider.getValueIsAdjusting()) {
                addUndo("Arrow Size Ratio");
            }
        }
    }//GEN-LAST:event_arrowSizeRatioSliderStateChanged

    private void addLayerButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addLayerButtonActionPerformed
        openShapefile();
    }//GEN-LAST:event_addLayerButtonActionPerformed

    private void jButton1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButton1ActionPerformed
        removeSelectedLayer();
    }//GEN-LAST:event_jButton1ActionPerformed

    private void openSettingsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_openSettingsMenuItemActionPerformed
        String filePath = FileUtils.askFile(null, "Load XML Settings", null, true, "xml");
        if (filePath != null) {
            try {
                Model newModel = Model.unmarshal(filePath);
                model.copyTransientFields(newModel);
                setModel(newModel);
                mapComponent.zoomOnRectangle(model.getFlowsBoundingBox());
            } catch (Throwable ex) {
                showErrorDialog("Could not read the file.", ex);
            }
        }
    }//GEN-LAST:event_openSettingsMenuItemActionPerformed

    private void saveSettingsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveSettingsMenuItemActionPerformed
        try {
            // ask user for file
            String name = getTitle() + ".xml";
            String filePath = FileUtils.askFile(this, "Save Settings to XML File", name, false, "xml");
            if (filePath == null) {
                // user canceled
                return;
            }
            File file = new File(filePath);
            model.marshal(file.getAbsolutePath());
        } catch (Throwable ex) {
            showErrorDialog("Could not save settings to XML file.", ex);
        }
    }//GEN-LAST:event_saveSettingsMenuItemActionPerformed

    private void exportFlowsCSVMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportFlowsCSVMenuItemActionPerformed
        try {
            // ask for export file
            String name = getTitle() + ".csv";
            String outFilePath = FileUtils.askFile(this, "CSV Text File", name, false, "csv");
            if (outFilePath == null) {
                // user canceled
                return;
            }
            CSVFlowExporter.export(outFilePath, model.flowIterator());
        } catch (Throwable ex) {
            showErrorDialog("Could not export flows to CSV text file.", ex);
        }
    }//GEN-LAST:event_exportFlowsCSVMenuItemActionPerformed

    private void addFlowToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_addFlowToggleButtonActionPerformed
        mapComponent.setMapTool(new AddFlowTool(mapComponent, model));
    }//GEN-LAST:event_addFlowToggleButtonActionPerformed

    private void maximumFlowWidthSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_maximumFlowWidthSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setMaxFlowStrokeWidthPixel(maximumFlowWidthSlider.getValue());
            mapComponent.refreshMap();
            if (!maximumFlowWidthSlider.getValueIsAdjusting()) {
                addUndo("Flow Width");
            }
        }
    }//GEN-LAST:event_maximumFlowWidthSliderStateChanged

    private void maximumNodeSizeSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_maximumNodeSizeSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setMaxNodeSizePx(maximumNodeSizeSlider.getValue());
            mapComponent.refreshMap();
            if (!maximumNodeSizeSlider.getValueIsAdjusting()) {
                addUndo("Node Size");
            }
        }
    }//GEN-LAST:event_maximumNodeSizeSliderStateChanged

    private void lowFlowSegmentationMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_lowFlowSegmentationMenuItemActionPerformed
        if (lowFlowSegmentationMenuItem.isSelected()) {
            model.setFlowNodeDensity(FlowNodeDensity.LOW);
            mapComponent.refreshMap();
        }

    }//GEN-LAST:event_lowFlowSegmentationMenuItemActionPerformed

    private void mediumFlowSegmentationMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mediumFlowSegmentationMenuItemActionPerformed
        if (mediumFlowSegmentationMenuItem.isSelected()) {
            model.setFlowNodeDensity(FlowNodeDensity.MEDIUM);
            mapComponent.refreshMap();
        }
    }//GEN-LAST:event_mediumFlowSegmentationMenuItemActionPerformed

    private void highFlowSegmentationMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_highFlowSegmentationMenuItemActionPerformed
        if (highFlowSegmentationMenuItem.isSelected()) {
            model.setFlowNodeDensity(FlowNodeDensity.HIGH);
            mapComponent.refreshMap();
        }
    }//GEN-LAST:event_highFlowSegmentationMenuItemActionPerformed

    private void showFlowSegmentsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showFlowSegmentsMenuItemActionPerformed
        mapComponent.setDrawLineSegments(!mapComponent.isDrawLineSegments());
        if (mapComponent.isDrawLineSegments()) {
            showFlowSegmentsMenuItem.setText("Hide Flow Points");
        } else {
            showFlowSegmentsMenuItem.setText("Show Flow Points");
        }
        mapComponent.refreshMap();
    }//GEN-LAST:event_showFlowSegmentsMenuItemActionPerformed

    private void viewCanvasToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_viewCanvasToggleButtonActionPerformed
        mapComponent.setDrawCanvas(viewCanvasToggleButton.isSelected());
        mapComponent.refreshMap();
    }//GEN-LAST:event_viewCanvasToggleButtonActionPerformed

    private void viewFlowRangeboxToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_viewFlowRangeboxToggleButtonActionPerformed
        mapComponent.setDrawFlowRangebox(viewFlowRangeboxToggleButton.isSelected());
        mapComponent.refreshMap();
    }//GEN-LAST:event_viewFlowRangeboxToggleButtonActionPerformed

    private void editMenuMenuSelected(javax.swing.event.MenuEvent evt) {//GEN-FIRST:event_editMenuMenuSelected
        boolean hasSelectedFlow = model.isFlowSelected();
        boolean hasSelectedNode = model.isNodeSelected();
        boolean isLockedFlowSelected = model.isLockedFlowSelected();
        boolean isUnlockedFlowSelected = model.isUnlockedFlowSelected();
        deleteMenuItem.setEnabled(hasSelectedFlow || hasSelectedNode);
        selectAllMenuItem.setEnabled(model.getNbrFlows() > 1 || model.getNbrNodes() > 1);
        selectNoneMenuItem.setEnabled(hasSelectedFlow || hasSelectedNode);
        lockMenuItem.setEnabled(isUnlockedFlowSelected);
        unlockMenuItem.setEnabled(isLockedFlowSelected);
        reverseFlowDirectionMenuItem.setEnabled(hasSelectedFlow);
        straightenFlowsMenuItem.setEnabled(hasSelectedFlow);
    }//GEN-LAST:event_editMenuMenuSelected

    private void straightenFlowsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_straightenFlowsMenuItemActionPerformed
        ForceLayouter layouter = new ForceLayouter(model);
        layouter.straightenFlows(true);
        addUndo("Straighten Flows");
        mapComponent.refreshMap();
    }//GEN-LAST:event_straightenFlowsMenuItemActionPerformed

    private void reverseFlowDirectionMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_reverseFlowDirectionMenuItemActionPerformed
        model.reverseSelectedFlows();
        addUndo("Reverse Flow Direction");
        mapComponent.refreshMap();
    }//GEN-LAST:event_reverseFlowDirectionMenuItemActionPerformed

    private void unlockMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_unlockMenuItemActionPerformed
        model.setLockOfSelectedFlows(false);
        addUndo("Unlock");
        lockUnlockButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Unlocked16x16.gif")));
        mapComponent.refreshMap();
    }//GEN-LAST:event_unlockMenuItemActionPerformed

    private void lockMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_lockMenuItemActionPerformed
        model.setLockOfSelectedFlows(true);
        addUndo("Lock");
        lockUnlockButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Locked16x16.gif")));
        mapComponent.refreshMap();

    }//GEN-LAST:event_lockMenuItemActionPerformed

    private void selectNoneMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectNoneMenuItemActionPerformed
        model.setSelectionOfAllFlowsAndNodes(false);

        setLockUnlockButtonIcon();

        mapComponent.refreshMap();
    }//GEN-LAST:event_selectNoneMenuItemActionPerformed

    private void selectAllMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectAllMenuItemActionPerformed
        model.setSelectionOfAllFlowsAndNodes(true);

        setLockUnlockButtonIcon();

        mapComponent.refreshMap();
    }//GEN-LAST:event_selectAllMenuItemActionPerformed

    /**
     * Sets the icon of the lockUnlockButton to the appropriate icon for the
     * locked status of selected flows. FIXME This code is repeated in the
     * SelectionTool. Any way it could access this method here instead? Or is
     * there some kind of action listener this code could go into that the
     * SelectionTool could trigger?
     */
    private void setLockUnlockButtonIcon() {
        ArrayList<Flow> selectedFlows = model.getSelectedFlows();
        if (selectedFlows.size() > 0) {
            lockUnlockButton.setEnabled(true);

            int locked = 0;
            int unlocked = 0;
            for (Flow flow : selectedFlows) {
                if (flow.isLocked()) {
                    locked++;
                } else {
                    unlocked++;
                }
            }
            if (locked + unlocked == 0) {
                lockUnlockButton.setEnabled(false);
            } else if (locked > 0 && unlocked == 0) {
                lockUnlockButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Locked16x16.gif")));
            } else if (unlocked > 0 && locked == 0) {
                lockUnlockButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Unlocked16x16.gif")));
            } else {
                lockUnlockButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/LockedUnlocked16x16.gif")));
            }
        } else {
            lockUnlockButton.setEnabled(false);
        }
    }

    private void deleteMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_deleteMenuItemActionPerformed
        addUndo("Delete");
        model.deleteSelectedFlowsAndNodes();
        mapComponent.refreshMap();
    }//GEN-LAST:event_deleteMenuItemActionPerformed

    private void redoMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_redoMenuItemActionPerformed
        undoRedo(false);
    }//GEN-LAST:event_redoMenuItemActionPerformed

    private void undoMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_undoMenuItemActionPerformed
        undoRedo(true);
    }//GEN-LAST:event_undoMenuItemActionPerformed

    private void lockUnlockButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_lockUnlockButtonActionPerformed
        ArrayList<Flow> selectedFlows = model.getSelectedFlows();
        int locked = 0; // FIXME not used
        int unlocked = 0;
        for (Flow flow : selectedFlows) {
            if (flow.isLocked()) {
                locked++;
            } else {
                unlocked++;
            }
        }

        if (unlocked == 0) {
            model.setLockOfSelectedFlows(false);
            lockUnlockButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Unlocked16x16.gif")));
            addUndo("Unlock");
        } else {
            model.setLockOfSelectedFlows(true);
            lockUnlockButton.setIcon(new javax.swing.ImageIcon(getClass().getResource("/edu/oregonstate/cartography/icons/Locked16x16.gif")));
            addUndo("Lock");
        }
        mapComponent.refreshMap();

    }//GEN-LAST:event_lockUnlockButtonActionPerformed

    private void enforceCanvasCheckBoxMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_enforceCanvasCheckBoxMenuItemActionPerformed
        model.setEnforceCanvasRange(enforceCanvasCheckBoxMenuItem.isSelected());
        layout("");
    }//GEN-LAST:event_enforceCanvasCheckBoxMenuItemActionPerformed

    /**
     * Sets the x coordinate of selected nodes to the value that was just
     * entered into this text box.
     *
     * @param evt
     */
    private void xFormattedTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_xFormattedTextFieldActionPerformed

        if (model != null) {
            try {
                xFormattedTextField.commitEdit();
            } catch (ParseException ex) {
                // the text field does not currently contain a valid value
                return;
            }
            if (xFormattedTextField.getValue() != null) {
                double x = ((Number) xFormattedTextField.getValue()).doubleValue();
                ArrayList<Point> selectedNodes = model.getSelectedNodes();
                for (Point node : selectedNodes) {
                    node.x = x;
                }
            }
            mapComponent.refreshMap();
        }
        // Move focus to MainWindow
        this.requestFocus();
        addUndo("Edit X Coordinate");
    }//GEN-LAST:event_xFormattedTextFieldActionPerformed

    /**
     * Sets the Y coordinate of selected nodes to the value that was just
     * entered into this text box.
     *
     * @param evt
     */
    private void yFormattedTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_yFormattedTextFieldActionPerformed
        if (model != null) {
            try {
                yFormattedTextField.commitEdit();
            } catch (ParseException ex) {
                // the text field does not currently contain a valid value
                return;
            }
            if (yFormattedTextField.getValue() != null) {
                double y = ((Number) yFormattedTextField.getValue()).doubleValue();
                ArrayList<Point> selectedNodes = model.getSelectedNodes();
                for (Point node : selectedNodes) {
                    node.y = y;
                }
            }
            mapComponent.refreshMap();
        }
        // Move focus to MainWindow
        this.requestFocus();
        addUndo("Edit Y Coordinate");
    }//GEN-LAST:event_yFormattedTextFieldActionPerformed

    /**
     * Sets the value of any selected features to the value that was just
     * entered into this text box.
     *
     * @param evt
     */
    private void valueFormattedTextFieldActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_valueFormattedTextFieldActionPerformed
        if (model != null) {
            // Makes sure the value of the box is the thing that changed.
            try {
                valueFormattedTextField.commitEdit();
            } catch (ParseException ex) {
                // the text field does not currently contain a valid value
                return;
            }
            // Get the value of the field and pass it to the model
            double v = ((Number) valueFormattedTextField.getValue()).doubleValue();
            model.setValueOfSelectedFlows(v);
            model.setValueOfSelectedNodes(v);
            mapComponent.refreshMap();
        }
        // Move focus to MainWindow
        this.requestFocus();
        addUndo("Edit Value");
    }//GEN-LAST:event_valueFormattedTextFieldActionPerformed

    private void showFlowsToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showFlowsToggleButtonActionPerformed
        mapComponent.setDrawFlows(showFlowsToggleButton.isSelected());
        mapComponent.refreshMap();
    }//GEN-LAST:event_showFlowsToggleButtonActionPerformed

    private void showNodesToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_showNodesToggleButtonActionPerformed
        mapComponent.setDrawNodes(showNodesToggleButton.isSelected());
        mapComponent.refreshMap();
    }//GEN-LAST:event_showNodesToggleButtonActionPerformed

    private double inverseDistanceWeight(double d) {
        // FIXME hard coded exponent value
        double p = 2;
        return 1. / Math.pow(d, p);
    }

    private void emptySpaceMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_emptySpaceMenuItemActionPerformed
        // FIXME this is not the right class for this

        // experiment for moving flow towards empty space. Attracting forces are 
        // computed between white space and the control point of the flow.
        // It might be better to compute forces between empty space and flow line
        // segments, because the control point can be quite distant from the 
        // flow line.
        if (model.getSelectedFlows().size() < 1) {
            System.err.println("no flow selected");
            return;
        }

        // FIXME hard coded tesselation size of the map space
        int size = 1000;
        Rectangle2D bb = mapComponent.getVisibleArea();
        BufferedImage image = FloxRenderer.renderToImage(model, size, bb,
                false, // antialiasing
                false, // draw background 
                true, // fill node circles
                false, // draw selected flows 
                true, // draw flows
                true); // draw nodes

        // display the image
        // edu.oregonstate.cartography.utils.ImageUtils.displayImageInWindow(image);
        // convert image to a boolean grid
        // false values are not occupied by flows or nodes, true values are occupied.
        int cols = image.getWidth();
        int rows = image.getHeight();
        double cellSize = bb.getWidth() / (cols - 1);
        BooleanGrid booleanGrid = new BooleanGrid(cols, rows, cellSize);
        booleanGrid.setWest(bb.getMinX());
        booleanGrid.setNorth(bb.getMaxY());
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                // FIXME this is slow and could be accelarated by accessing the
                // raster model.
                // to acclerate, we could also render to a B/W image.
                int rgb = image.getRGB(c, r);
                int b = rgb & 0xFF;
                booleanGrid.setValue(b == 0, c, r);
            }
        }
        System.out.println(booleanGrid.toString());

        // get first selected flow. 
        // FIXME Ignore other selected flows for the moment.
        Flow selectedFlow = model.getSelectedFlows().get(0);
        Point ctrlPt = selectedFlow.getCtrlPt();

        // find attracting forces on the selected flow
        double vx = 0;
        double vy = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean attractor = booleanGrid.getValue(c, r) == false;
                if (attractor) {
                    double cellX = booleanGrid.getWest() + c * booleanGrid.getCellSize();
                    double cellY = booleanGrid.getNorth() - r * booleanGrid.getCellSize();
                    double dx = cellX - ctrlPt.x;
                    double dy = cellY - ctrlPt.y;
                    double d = Math.sqrt(dx * dx + dy * dy);
                    double idw = inverseDistanceWeight(d);

                    // direction vector with length == 1
                    dx /= d;
                    dy /= d;

                    // weight direction vector with inverse distance weight
                    vx += dx * idw;
                    vy += dy * idw;
                }
            }
        }

        // FIXME hard-coded weight factor. Should be entered by user with GUI.
        double attractorWeight = 0.2;

        // we are only interested in the direction of the total attracting white space
        Force v = new Force(vx, vy);
        v.normalize();

        // Multiply by the value of the GUI slider for attractor weight.
        v.scale(attractorWeight);

        // move control point
        ctrlPt.x += v.fx;
        ctrlPt.y += v.fy;

        mapComponent.eraseBufferImage();
        mapComponent.repaint();
    }//GEN-LAST:event_emptySpaceMenuItemActionPerformed

    private void lockFeatureScaleToggleButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_lockFeatureScaleToggleButtonActionPerformed
        if (lockFeatureScaleToggleButton.isSelected()) {
            lockFeatureScaleToggleButton.setText("Unlock Feature Scale");
            model.setScaleLocked(true);
            model.setLockedMapScale(mapComponent.getScale());
        } else {
            lockFeatureScaleToggleButton.setText("Lock Feature Scale");
            model.setScaleLocked(false);
            mapComponent.refreshMap();
        }
    }//GEN-LAST:event_lockFeatureScaleToggleButtonActionPerformed

    private void angularDistributionSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_angularDistributionSliderStateChanged
        model.setAngularDistributionWeight(angularDistributionSlider.getValue() / 100d);
        mapComponent.refreshMap();
        if (angularDistributionSlider.getValueIsAdjusting() == false) {
            layout("Angular Distribution");
        }
    }//GEN-LAST:event_angularDistributionSliderStateChanged

    private void moveFlowsCheckBoxMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_moveFlowsCheckBoxMenuItemActionPerformed
        layout("Move Flows");
    }//GEN-LAST:event_moveFlowsCheckBoxMenuItemActionPerformed

    private void useFrictionCheckBoxMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_useFrictionCheckBoxMenuItemActionPerformed
        model.useFrictionForForcesHack = useFrictionCheckBoxMenuItem.isSelected();
        layout("Use Friction for Forces");
    }//GEN-LAST:event_useFrictionCheckBoxMenuItemActionPerformed

    private void recomputeMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_recomputeMenuItemActionPerformed
        layout(null);
    }//GEN-LAST:event_recomputeMenuItemActionPerformed

    private void flowDistanceFromStartPointFormattedTextFieldPropertyChange(java.beans.PropertyChangeEvent evt) {//GEN-FIRST:event_flowDistanceFromStartPointFormattedTextFieldPropertyChange
        if ("value".equals(evt.getPropertyName()) && model != null) {
            double s = ((Number) flowDistanceFromStartPointFormattedTextField.getValue()).doubleValue();
            model.setFlowDistanceFromStartPointPixel(s);
            mapComponent.refreshMap();
        }
    }//GEN-LAST:event_flowDistanceFromStartPointFormattedTextFieldPropertyChange

    private void arrowLengthRatioSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_arrowLengthRatioSliderStateChanged
        if (updatingGUI == false && model != null) {
            model.setArrowLengthRatio(Math.abs(arrowLengthRatioSlider.getValue() - 100) / 100d);
            updateArrowHeads();
            mapComponent.refreshMap();
            if (!arrowLengthRatioSlider.getValueIsAdjusting()) {
                addUndo("Arrow Size Ratio");
            }
        }
    }//GEN-LAST:event_arrowLengthRatioSliderStateChanged

    private void useInFlowCheckboxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_useInFlowCheckboxActionPerformed
        if (model != null) {
            model.setPointArrowTowardsEndpoint(useInFlowCheckbox.isSelected());
            updateArrowHeads();
            mapComponent.refreshMap();
            addUndo("Use In Flow");
        }
    }//GEN-LAST:event_useInFlowCheckboxActionPerformed

    private void useAngularFrictionCheckBoxMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_useAngularFrictionCheckBoxMenuItemActionPerformed
        model.useFrictionForAngularDistortionHack = useAngularFrictionCheckBoxMenuItem.isSelected();
        layout("Use Friction for Angular Distribution");
    }//GEN-LAST:event_useAngularFrictionCheckBoxMenuItemActionPerformed

    private void liveDrawingCheckBoxMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_liveDrawingCheckBoxMenuItemActionPerformed
        model.liveDrawing = liveDrawingCheckBoxMenuItem.isSelected();
    }//GEN-LAST:event_liveDrawingCheckBoxMenuItemActionPerformed

    private void drawInlineArrowsCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_drawInlineArrowsCheckBoxActionPerformed
        model.setDrawInlineArrows(drawInlineArrowsCheckBox.isSelected());
        mapComponent.refreshMap();
        addUndo("Draw Inline Arrows");
    }//GEN-LAST:event_drawInlineArrowsCheckBoxActionPerformed

    private void limitNodesRepulsionToBandCheckBoxActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_limitNodesRepulsionToBandCheckBoxActionPerformed
        model.limitNodesRepulsionToBandHack = limitNodesRepulsionToBandCheckBox.isSelected();
        layout("Limit Nodes Repulsion to Band");
    }//GEN-LAST:event_limitNodesRepulsionToBandCheckBoxActionPerformed

    private void minPxDistanceOfFlowsFromNodesSliderStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_minPxDistanceOfFlowsFromNodesSliderStateChanged
        model.setNodeTolerancePx(minPxDistanceOfFlowsFromNodesSlider.getValue());
        mapComponent.refreshMap();
        if (minPxDistanceOfFlowsFromNodesSlider.getValueIsAdjusting() == false) {
            layout("Min. Flow Distance from Nodes");
        }
    }//GEN-LAST:event_minPxDistanceOfFlowsFromNodesSliderStateChanged

    /**
     * FIXME This will result in concurrent unsynchronized modifications of the
     * model. The Event Dispatch Thread is drawing the model, while the worker
     * is simultaneously changing it.
     */
    private class LayoutWorker extends SwingWorker<Void, Void> {

        private final ForceLayouter layouter;

        public LayoutWorker(ForceLayouter layouter) {
            this.layouter = layouter;
            this.addPropertyChangeListener(new PropertyChangeListener() {

                @Override
                public void propertyChange(PropertyChangeEvent evt) {
                    if ("progress".equals(evt.getPropertyName())) {
                        progressBar.setValue((Integer) evt.getNewValue());
                    }
                }
            });
        }

        /**
         * Apply layout iterations to all non-locked flows.
         */
        private void layout(int start, int end,
                boolean moveFlowsOverlappingNodes,
                double scale) {

            for (int i = start; i < end; i++) {
                if (isCancelled()) {
                    break;
                }

                // compute an iteration with decreasing weight
                double weight = 1d - (double) i / ForceLayouter.NBR_ITERATIONS;
                layouter.layoutAllFlows(weight);

                if (moveFlowsOverlappingNodes) {
                    // store initial lock flags of all flows
                    boolean[] initialLocks = model.getLocks();

                    // move flows: this will lock flows that have been moved
                    layouter.moveFlowsOverlappingNodes(scale);

                    // reset lock flags to initial values
                    model.applyLocks(initialLocks);
                }

                // publish intermediate results in map. This will call process() 
                // on the Event Dispatch Thread.
                if (model.liveDrawing) {
                    publish();
                }

                // update progress indicator
                double progress = 100d * i / ForceLayouter.NBR_ITERATIONS;
                setProgress((int) Math.round(progress));
            }
        }

        @Override
        public Void doInBackground() {
            // initialize progress property.
            double startTime = System.currentTimeMillis();
            
            setProgress(0);

            double scale = mapComponent.getScale();

            // first half of iterations. Flows are not moved away from overlapped nodes.
            layout(0, ForceLayouter.NBR_ITERATIONS / 2, false, scale);

            // second half of iterations: Flows are moved away from overlapped nodes.
            boolean moveFlowsOverlappingNodes = moveFlowsCheckBoxMenuItem.isSelected();
            layout(ForceLayouter.NBR_ITERATIONS / 2, ForceLayouter.NBR_ITERATIONS,
                    moveFlowsOverlappingNodes, scale);

            layouter.computeArrowHeads(scale);
            System.out.println("Milliseconds: " + (System.currentTimeMillis() - startTime));
            return null;
        }

        /**
         * Finished computations. This is invoked on the Event Dispatch Thread.
         */
        @Override
        public void done() {
            try {
                if (!isCancelled()) {
                    get();
                    mapComponent.eraseBufferImage();
                    mapComponent.repaint();
                    progressBar.setVisible(false);
                }
            } catch (Throwable t) {
                showErrorDialog("An error occured while computing a new layout.", t);
            }
        }

        /**
         * Process intermediate results. This is invoked on the Event Dispatch
         * Thread.
         *
         * @param models
         */
        @Override
        protected void process(List<Void> ignore) {
            // draw the new graph on the map
            mapComponent.eraseBufferImage();
            mapComponent.repaint();
        }
    }

    private void layout(String undoString) {
        if (updatingGUI) {
            return;
        }
        if (undoString != null) {
            addUndo(undoString);
        }

        // If there are no flows, exit the method.
        if (model.getNbrFlows() == 0) {
            return;
        }

        progressBar.setVisible(true);
        if (layoutWorker != null && !layoutWorker.isDone()) {
            layoutWorker.cancel(false);
        }

        // Create a layouter and pass it the model.
        ForceLayouter layouter = new ForceLayouter(model);
        layouter.straightenFlows(false);
        layoutWorker = new LayoutWorker(layouter);
        layoutWorker.execute();
    }

    private void updateArrowHeads() {
        if (updatingGUI || model.getNbrFlows() == 0) {
            return;
        }
        ForceLayouter layouter = new ForceLayouter(model);
        double scale = mapComponent.getScale();
        layouter.computeArrowHeads(scale);
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JCheckBox addArrowsCheckbox;
    private javax.swing.JToggleButton addFlowToggleButton;
    private javax.swing.JButton addLayerButton;
    private javax.swing.JSlider angularDistributionSlider;
    private javax.swing.JSlider antiTorsionSlider;
    private javax.swing.JSlider arrowCornerPositionSlider;
    private javax.swing.JSlider arrowEdgeCtrlLengthSlider;
    private javax.swing.JSlider arrowEdgeCtrlWidthSlider;
    private javax.swing.JPanel arrowHeadsControlPanel;
    private javax.swing.JPanel arrowHeadsPanel;
    private javax.swing.JSlider arrowLengthRatioSlider;
    private javax.swing.JSlider arrowSizeRatioSlider;
    private javax.swing.JToggleButton arrowToggleButton;
    private javax.swing.JSlider arrowheadLengthSlider;
    private javax.swing.JSlider arrowheadWidthSlider;
    private javax.swing.ButtonGroup buttonGroup1;
    private javax.swing.JSlider canvasSizeSlider;
    private javax.swing.JPanel clipAreaControlPanel;
    private javax.swing.JPanel clipAreaPanel;
    private javax.swing.JCheckBox clipWithEndAreasCheckBox;
    private javax.swing.JCheckBox clipWithStartAreasCheckBox;
    private javax.swing.JTabbedPane controlsTabbedPane;
    private edu.oregonstate.cartography.flox.gui.CoordinateInfoPanel coordinateInfoPanel;
    private javax.swing.JMenuItem deleteMenuItem;
    private javax.swing.JToggleButton distanceToggleButton;
    private javax.swing.JCheckBox drawEndClipAreasCheckBox;
    private javax.swing.JCheckBox drawInlineArrowsCheckBox;
    private javax.swing.JCheckBox drawStartClipAreasCheckBox;
    private javax.swing.JMenu editMenu;
    private javax.swing.JMenuItem emptySpaceMenuItem;
    private javax.swing.JFormattedTextField endAreasBufferDistanceFormattedTextField;
    private javax.swing.JCheckBoxMenuItem enforceCanvasCheckBoxMenuItem;
    private javax.swing.JCheckBox enforceRangeboxCheckbox;
    private javax.swing.JSlider exponentSlider;
    private javax.swing.JMenuItem exportFlowsCSVMenuItem;
    private javax.swing.JMenuItem exportImageMenuItem;
    private javax.swing.JMenuItem exportSVGMenuItem;
    private javax.swing.JMenu fileMenu;
    private javax.swing.JCheckBox fillCheckBox;
    private edu.oregonstate.cartography.flox.gui.ColorButton fillColorButton;
    private javax.swing.JFormattedTextField flowDistanceFromEndPointFormattedTextField;
    private javax.swing.JFormattedTextField flowDistanceFromStartPointFormattedTextField;
    private javax.swing.JSlider flowRangeboxSizeSlider;
    private javax.swing.JMenu flowSegmentationMenu;
    private javax.swing.JLabel flowsFilePathLabel;
    private javax.swing.JMenuItem floxReportMenuItem;
    private javax.swing.JPanel forcesPanel;
    private javax.swing.JToggleButton handToggleButton;
    private javax.swing.JRadioButtonMenuItem highFlowSegmentationMenuItem;
    private javax.swing.JMenuItem importFlowsMenuItem;
    private javax.swing.JPanel importPanel;
    private javax.swing.JButton importPanelCancelButton;
    private javax.swing.JButton importPanelOKButton;
    private javax.swing.JMenu infoMenu;
    private javax.swing.JMenuItem infoMenuItem;
    private javax.swing.JButton jButton1;
    private javax.swing.JCheckBoxMenuItem jCheckBoxMenuItem1;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel10;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel12;
    private javax.swing.JLabel jLabel13;
    private javax.swing.JLabel jLabel14;
    private javax.swing.JLabel jLabel15;
    private javax.swing.JLabel jLabel16;
    private javax.swing.JLabel jLabel17;
    private javax.swing.JLabel jLabel18;
    private javax.swing.JLabel jLabel19;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel22;
    private javax.swing.JLabel jLabel23;
    private javax.swing.JLabel jLabel25;
    private javax.swing.JLabel jLabel26;
    private javax.swing.JLabel jLabel27;
    private javax.swing.JLabel jLabel29;
    private javax.swing.JLabel jLabel30;
    private javax.swing.JMenu jMenu1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JPopupMenu.Separator jSeparator10;
    private javax.swing.JPopupMenu.Separator jSeparator11;
    private javax.swing.JPopupMenu.Separator jSeparator13;
    private javax.swing.JPopupMenu.Separator jSeparator14;
    private javax.swing.JPopupMenu.Separator jSeparator7;
    private javax.swing.JPopupMenu.Separator jSeparator8;
    private javax.swing.JSeparator jSeparator9;
    private javax.swing.JTextArea jTextArea1;
    private javax.swing.JTextArea jTextArea2;
    private javax.swing.JToolBar jToolBar1;
    private edu.oregonstate.cartography.flox.gui.DraggableList layerList;
    private javax.swing.JScrollPane layerListScrollPane;
    private javax.swing.JCheckBox limitNodesRepulsionToBandCheckBox;
    private javax.swing.JCheckBoxMenuItem liveDrawingCheckBoxMenuItem;
    private javax.swing.JToggleButton lockFeatureScaleToggleButton;
    private javax.swing.JMenuItem lockMenuItem;
    private javax.swing.JButton lockUnlockButton;
    private javax.swing.JSlider longestFlowStiffnessSlider;
    private javax.swing.JRadioButtonMenuItem lowFlowSegmentationMenuItem;
    private edu.oregonstate.cartography.flox.gui.FloxMapComponent mapComponent;
    private javax.swing.JPanel mapControlPanel;
    private javax.swing.JMenu mapMenu;
    private javax.swing.JPanel mapPanel;
    private javax.swing.ButtonGroup mapToolsButtonGroup;
    private javax.swing.JSlider maximumFlowWidthSlider;
    private javax.swing.JSlider maximumNodeSizeSlider;
    private javax.swing.JRadioButtonMenuItem mediumFlowSegmentationMenuItem;
    private javax.swing.JMenuBar menuBar;
    private javax.swing.JSlider minPxDistanceOfFlowsFromNodesSlider;
    private javax.swing.JLabel minPxDistanceOfFlowsFromNodesSliderLabel;
    private javax.swing.JCheckBoxMenuItem moveFlowsCheckBoxMenuItem;
    private javax.swing.JSlider nodeWeightSlider;
    private javax.swing.JMenuItem openPointsAndFlowsMenuItem;
    private javax.swing.JMenuItem openSettingsMenuItem;
    private javax.swing.JMenuItem openShapefileMenuItem;
    private javax.swing.JSlider peripheralStiffnessSlider;
    private javax.swing.JLabel pointsFilePathLabel;
    private javax.swing.JProgressBar progressBar;
    private javax.swing.JPanel progressBarPanel;
    private javax.swing.JMenuItem recomputeMenuItem;
    private javax.swing.JMenuItem redoMenuItem;
    private javax.swing.JMenuItem removeAllLayersMenuItem;
    private javax.swing.JMenuItem removeSelectedLayerMenuItem;
    private javax.swing.JMenuItem reverseFlowDirectionMenuItem;
    private javax.swing.JPanel rightPanel;
    private javax.swing.JMenuItem saveSettingsMenuItem;
    private javax.swing.JMenuItem selectAllMenuItem;
    private javax.swing.JButton selectEndClipAreaButton;
    private javax.swing.JButton selectFlowsFileButton;
    private javax.swing.JMenuItem selectNoneMenuItem;
    private javax.swing.JButton selectPointsFileButton;
    private javax.swing.JButton showAllButton;
    private javax.swing.JMenuItem showAllMenuItem;
    private javax.swing.JMenuItem showAllMenuItem1;
    private javax.swing.JMenuItem showFlowSegmentsMenuItem;
    private javax.swing.JToggleButton showFlowsToggleButton;
    private javax.swing.JToggleButton showNodesToggleButton;
    private javax.swing.JFormattedTextField startAreasBufferDistanceFormattedTextField;
    private javax.swing.JMenuItem straightenFlowsMenuItem;
    private javax.swing.JCheckBox strokeCheckBox;
    private edu.oregonstate.cartography.flox.gui.ColorButton strokeColorButton;
    private javax.swing.JPanel symbolPanel;
    private javax.swing.JMenuItem undoMenuItem;
    private javax.swing.JMenuItem unlockMenuItem;
    private javax.swing.JCheckBoxMenuItem useAngularFrictionCheckBoxMenuItem;
    private javax.swing.JCheckBoxMenuItem useFrictionCheckBoxMenuItem;
    private javax.swing.JCheckBox useInFlowCheckbox;
    private javax.swing.JLabel vallueLabel;
    private javax.swing.JFormattedTextField valueFormattedTextField;
    private javax.swing.JToggleButton viewCanvasToggleButton;
    private javax.swing.JToggleButton viewFlowRangeboxToggleButton;
    private javax.swing.JMenu viewMenu;
    private javax.swing.JMenuItem viewZoomInMenuItem;
    private javax.swing.JMenuItem viewZoomOutMenuItem;
    private javax.swing.JFormattedTextField xFormattedTextField;
    private javax.swing.JFormattedTextField yFormattedTextField;
    private javax.swing.JSlider zeroLengthStiffnessSlider;
    private javax.swing.JToggleButton zoomInToggleButton;
    private javax.swing.JMenuItem zoomOnSelectedLayerMenuItem;
    private javax.swing.JToggleButton zoomOutToggleButton;
    // End of variables declaration//GEN-END:variables

}
