package ini.trakem2.display;

import ini.trakem2.Project;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.M;
import ini.trakem2.utils.IJError;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.Composite;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.util.Set;
import java.util.List;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.awt.event.MouseEvent;
import ij.measure.Calibration;
import ij.measure.ResultsTable;

/** A one-to-many connection, represented by one source point and one or more target points. The connector is drawn by click+drag+release, defining the origin at click and the target at release. By clicking anywhere else, the connector can be given another target. Points can be dragged and removed.
 * Connectors are meant to represent synapses, in particular polyadic synapses. */
public class Connector extends ZDisplayable implements VectorData {

	/** Represents points as X1,Y1,X2,Y2,... */
	private float[] p = null;
	private long[] lids = null;
	private float[] radius = null;

	static private float last_radius = 0;

	public Connector(Project project, String title) {
		super(project, title, 0, 0);
	}

	public Connector(Project project, long id, String title, float alpha, boolean visible, Color color, boolean locked, AffineTransform at) {
		super(project, id, title, locked, at, 0, 0);
		this.alpha = alpha;
		this.visible = visible;
		this.color = color;
	}

	public Connector(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
		String origin = (String) ht_attr.get("origin");
		String targets = (String) ht_attr.get("targets");
		if (null != origin) {
			String[] o = origin.split(",");
			String[] t = null;
			int len = 1;
			boolean new_format = 0 == o.length % 4;
			if (null != targets) {
				t = targets.split(",");
				if (new_format) {
					// new format, with radii
					len += t.length / 4;
				} else {
					// old format, without radii
					len += t.length / 3;
				}
			}
			this.p = new float[len + len];
			this.lids = new long[len];
			this.radius = new float[len];

			// Origin:
			/* X  */ p[0] = Float.parseFloat(o[0]);
			/* Y  */ p[1] = Float.parseFloat(o[1]);
			/* LZ */ lids[0] = Long.parseLong(o[2]);
			if (new_format) {
				radius[0] = Float.parseFloat(o[3]);
			}

			// Targets:
			if (null != targets && targets.length() > 0) {
				int inc = new_format ? 4 : 3;
				for (int i=0, k=1; i<t.length; i+=inc, k++) {
					/* X  */ p[k+k] = Float.parseFloat(t[i]);
					/* Y  */ p[k+k+1] = Float.parseFloat(t[i+1]);
					/* LZ */ lids[k] = Long.parseLong(t[i+2]);
					if (new_format) radius[k] = Float.parseFloat(t[i+3]);
				}
			}
			if (!new_format) calculateBoundingBox();
		}
		// TODO: to parse origin and targets with good performance, I'd have to create a custom NumberReader on a StringReader
		// that would read batches of 3 numbers as two floats and a long.
	}

	final private void resizeArray(int inc) {
		final float[] p2 = new float[p.length + inc + inc];
		System.arraycopy(p, 0, p2, 0, inc > 0 ? p.length : p.length + inc);
		p = p2;
		final long[] lids2 = new long[lids.length + inc];
		System.arraycopy(lids, 0, lids2, 0, inc > 0 ? lids.length : lids.length + inc);
		lids = lids2;
		final float[] radius2 = new float[radius.length + inc];
		System.arraycopy(radius, 0, radius2, 0, inc > 0 ? radius.length : radius.length + inc);
		radius = radius2;
	}

	/** Set an origin and a target point.*/
	public void set(final float ox, final float oy, final long o_layer_id, final float tx, final float ty, final long t_layer_id) {
		p = new float[4];
		lids = new long[2];
		radius = new float[2]; // zeroes
		p[0] = ox;	p[1] = oy;	lids[0] = o_layer_id;
		p[2] = ty; 	p[3] = ty;	lids[1] = t_layer_id;
		calculateBoundingBox();
	}

	public int addTarget(final float x, final float y, final long layer_id, final float r) {
		if (null == p) {
			Utils.log2("No origin set!");
			return -1;
		}
		resizeArray(1);
		p[p.length-2] = x;
		p[p.length-1] = y;
		lids[lids.length-1] = layer_id;
		radius[radius.length-1] = r;
		return lids.length-1;
	}

	public int addTarget(final double x, final double y, final long layer_id, final double r) {
		return addTarget((float)x, (float)y, layer_id, (float)r);
	}

	protected void mergeTargets(final Connector c) throws NoninvertibleTransformException {
		if (null == c || null == c.lids) return;
		if (null == this.lids) {
			// use target root as root
			addTarget(c.p[0], c.p[1], c.lids[0], c.radius[0]);
		}
		final int next = lids.length;
		resizeArray(c.lids.length-1);
		// Add all targets (not the first one, which is the root)
		final float[] fp = new float[2];
		AffineTransform aff = new AffineTransform(c.at);
		aff.preConcatenate(this.at.createInverse());
		for (int i=next, k=1; k<c.lids.length; i++, k++) {
			fp[0] = c.p[k+k];
			fp[1] = c.p[k+k+1];
			aff.transform(fp, 0, fp, 0, 1);
			p[i+i] = fp[0];
			p[i+i+1] = fp[1];
			lids[i] = c.lids[k];
			radius[i] = c.radius[k]; // ignoring affine
		}
	}

	/** To remove a target point of index larger than zero. */
	public boolean removeTarget(final int index) {
		if (null == p) {
			Utils.log2("No origin set yet!");
			return false;
		}
		if (index <= 0) return false;
		final float[] p2 = new float[p.length -2];
		System.arraycopy(p, 0, p2, 0, index+index);
		System.arraycopy(p, index+index+2, p2, index+index, p.length -(index+index) -2);
		this.p = p2;
		final long[] lids2 = new long[lids.length -1];
		System.arraycopy(lids, 0, lids2, 0, index);
		System.arraycopy(lids, index+1, lids2, index, lids.length - index - 1);
		this.lids = lids2;
		final float[] radius2 = new float[radius.length -1];
		System.arraycopy(radius, 0, radius2, 0, index);
		System.arraycopy(radius, index+1, radius2, index, radius.length - index - 1);
		this.radius = radius2;
		return true;
	}

	/** Find a point with accuracy dependent on magnification. */
	private int findPoint(int x_p, int y_p, long layer_id, double mag) {
		double d = 10 / mag;
		if (d < 2) d = 2;
		int index = -1;
		double min_dist = Double.MAX_VALUE;
		for (int i=0, l=0; i<p.length; i+=2, l++) {
			if (layer_id != lids[l]) continue; // not in the right layer!
			double dist = Math.abs(x_p - p[i]) + Math.abs(y_p - p[i+1]);
			if (dist < min_dist && dist <= d) {
				min_dist = dist;
				index = l;
			}
		}
		return index;
	}

	static private int index = -1;

	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		// transform the x_p, y_p to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_p = (int)po.x;
			y_p = (int)po.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {

			final long layer_id = Display.getFrontLayer().getId();

			if (null == p || 0 == p.length) {
				p = new float[4];
				lids = new long[2];
				radius = new float[]{last_radius, last_radius};
				p[0] = x_p;	p[1] = y_p;
				p[2] = x_p;	p[3] = y_p;
				lids[0] = layer_id;	lids[1] = layer_id;
				// Start dragging target point
				index = 1;
				return;
			}

			index = findPoint(x_p, y_p, layer_id, mag);
			
			if (Utils.isControlDown(me) && me.isShiftDown()) {
				if (0 == index) {
					// Remove origin: remove the entire Connector
					if (remove2(true)) {
						index = -1;
						return;
					}
				} else if (index > 0) {
					// Remove target
					removeTarget(index);
					repaint(true);
					index = -1;
					return;
				}
			} else if (-1 == index) {
				// add target
				index = addTarget(x_p, y_p, layer_id, last_radius);
				repaint(false);
			}
		}
	}

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {

		if (-1 == index) return;

		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_d_old, y_d_old);
			x_d_old = (int)pdo.x;
			y_d_old = (int)pdo.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {
			if (me.isShiftDown()) {
				radius[index] = (float) Math.sqrt(Math.pow(p[index+index] - x_d, 2) + Math.pow(p[index+index+1] - y_d, 2));
				last_radius = radius[index];
				Utils.showStatus("radius: " + radius[index], false);
			} else {
				// Else drag point
				p[index+index] += x_d - x_d_old;
				p[index+index+1] += y_d - y_d_old;
			}

			repaint(false);
		}
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {

		if (-1 == index) return;

		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_r, y_r);
			x_r = (int)pdo.x;
			y_r = (int)pdo.y;
		}

		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool) {
			p[index+index] += x_d - x_r;
			p[index+index+1] += y_d - y_r;


			// Remove target if it's identical to source
			if (index > 0 && p[0] == p[index+index] && p[1] == p[index+index+1] && lids[0] == lids[index]) {
				removeTarget(index);
				//Utils.log("Removed a target identical to origin.");
			}

			repaint(true);
		}

		// reset
		index = -1;
	}

	protected void repaint(boolean repaint_navigator) {
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox(); // there are more efficient ways to compute the new box ... incuding not doing it at all and just repainting the whole visible canvas
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 10, repaint_navigator);
	}

	public void calculateBoundingBox() {
		double min_x = Double.MAX_VALUE;
		double min_y = Double.MAX_VALUE;
		double max_x = 0.0D;
		double max_y = 0.0D;

		for (int i=0, j=0; i<p.length; i+=2, j++) {
			final float x = p[i],
			            y = p[i+1],
				    r = radius[j];
			if (x -r < min_x) min_x = x -r;
			if (y -r < min_y) min_y = y -r;
			if (x +r > max_x) max_x = x +r;
			if (y +r > max_y) max_y = y +r;
		}

		this.width = max_x - min_x;
		this.height = max_y - min_y;

		for (int i=0; i<p.length; i+=2) {
			p[i] -= min_x;
			p[i+1] -= min_y;
		}
		this.at.translate(min_x, min_y);

		layer_set.updateBucket(this);
	}

	public Layer getFirstLayer() {
		Layer la = layer_set.getLayer(lids[0]);
		int i = layer_set.indexOf(la);
		for (int k=1; k<lids.length; k++) {
			Layer l = layer_set.getLayer(lids[k]);
			int j = layer_set.indexOf(l);
			if (j < i) {
				i = j;
				la = l;
			}
		}
		return la;
	}

	/** Expects Area in world coords. */
	public boolean intersects(Area area, double z_first, double z_last) {
		if (null == p || 0 == lids.length) return false; // empty
		try {
			area = area.createTransformedArea(this.at.createInverse());
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
			return false;
		}
		for (int i=0,j=0; i<lids.length; i++,j+=2) {
			double laz = layer_set.getLayer(lids[i]).getZ();
			if (laz < z_first || laz > z_last) continue;
			float r = radius[i];
			if (M.intersects(new Area(new Ellipse2D.Float(p[j] - r, p[j+1] - r, r+r, r+r)), area)) return true;
		}
		return false;
	}
	/** Expects Area in world coords. */
	public boolean intersects(Area area) {
		if (null == p || 0 == lids.length) return false; // empty
		try {
			area = area.createTransformedArea(this.at.createInverse());
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
			return false;
		}
		for (int i=0,j=0; i<lids.length; i++,j+=2) {
			float r = radius[i];
			if (M.intersects(new Area(new Ellipse2D.Float(p[j] - r, p[j+1] - r, r+r, r+r)), area)) return true;
		}
		return false;
	}
	/** Expects Area in world coords. */
	public boolean intersects(final Layer layer, Area area) {
		if (null == p || 0 == lids.length) return false; // empty
		try {
			area = area.createTransformedArea(this.at.createInverse());
		} catch (NoninvertibleTransformException nite) {
			IJError.print(nite);
			return false;
		}
		for (int i=0,j=0; i<lids.length; i++,j+=2) {
			if (layer.getId() == lids[i]) {
				float r = radius[i];
				if (M.intersects(new Area(new Ellipse2D.Float(p[j] - r, p[j+1] - r, r+r, r+r)), area)) return true;
			}
		}
		return false;
	}

	/** Expects Rectangle in world coords. */
	public boolean intersects(final Layer layer, final Rectangle r) {
		return intersects(layer, new Area(r));
	}

	public boolean linkPatches() {
		unlinkAll(Patch.class);
		boolean must_lock = false;
		for (int i=0; i<lids.length; i++) {
			Layer la = layer_set.getLayer(lids[i]);
			if (null == la) {
				Utils.log2("WARNING: could not find layer with id " + lids[i]);
				continue;
			}
			for (final Displayable d : la.find(Patch.class, (int)p[i+i], (int)p[i+i+1], true)) {
				link(d);
				if (d.locked) must_lock = true;
			}
		}
		if (must_lock && !locked) {
			setLocked(true);
			return true;
		}
		return false;
	}

	/** Returns the set of Displayable objects under the origin point, or an empty set if none. */
	public Set<Displayable> getOrigins(final Class c) {
		if (null == p) return new HashSet<Displayable>();
		return get(c, p[0], p[1], lids[0], radius[0]);
	}

	/** Returns the set of Displayable objects under the origin point, or an empty set if none. */
	public Set<Displayable> getOrigins() {
		return getOrigins(Displayable.class);
	}

	private final Set<Displayable> get(final Class c, final float x, final float y, final long layer_id, float r) {
		final Layer la = layer_set.getLayer(layer_id);
		float sc = (float)Math.max(Math.abs(at.getScaleX()),
			                   Math.max(Math.abs(at.getScaleY()),
					            Math.max(Math.abs(at.getShearX()),
						             Math.abs(at.getShearY()))));
		final float[] po = new float[]{x, y};
		this.at.transform(po, 0, po, 0, 1);
		r = r * sc;
		if (r <= 0) r = 1; // r is in pixels, so the minimal search when 0==r is for a circle of diameter 2 pixels.
		return new HashSet<Displayable>(layer_set.find(c, la, new Area(new Ellipse2D.Float(po[0]-r, po[1]-r, r+r, r+r)), true));
	}

	/** Returns the list of sets of visible Displayable objects under each target, or an empty list if none. */
	public List<Set<Displayable>> getTargets(final Class c) {
		final List<Set<Displayable>> al = new ArrayList<Set<Displayable>>();
		if (null == p) return al;
		for (int i=1; i<lids.length; i++) {
			al.add(get(c, p[i+i], p[i+i+1], lids[i], radius[i]));
		}
		return al;
	}

	/** Returns the list of sets of visible Displayable objects under each target, or an empty list if none. */
	public List<Set<Displayable>> getTargets() {
		return getTargets(Displayable.class);
	}

	public int getTargetCount() {
		if (null == p) return 0;
		return lids.length -1;
	}

	public void paint(Graphics2D g, Rectangle srcRect, double magnification, boolean active, int channels, Layer active_layer) {
		if (null == p) return;
		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}
		// apply transform
		final float[] p;
		if (!this.at.isIdentity()) p = transformPoints(this.p);
		else p = this.p;
		
		final boolean no_color_cues = "true".equals(project.getProperty("no_color_cues"));

		final long lid = active_layer.getId();
		final int i_current = layer_set.getLayerIndex(lid);

		for (int i=0; i<lids.length; i++) {
			// Which color?
			int ii = layer_set.getLayerIndex(lids[i]);
			if (ii == i_current -1 && !no_color_cues) g.setColor(Color.red);
			else if (ii == i_current) g.setColor(this.color);
			else if (ii == i_current + 1 && !no_color_cues) g.setColor(Color.blue);
			else continue; //don't paint!
			// Paint from point to half the other point
			if (0 == i) {
				// From the origin to half-way to each target
				for (int k=1; k<lids.length; k++) {
					g.drawLine((int)p[0], (int)p[1],
						   (int)(p[0] + (p[k+k] - p[0])/2), (int)(p[1] + (p[k+k+1] - p[1])/2));
				}
			} else {
				// From a target to half-way to the origin
				g.drawLine((int)p[i+i], (int)p[i+i+1],
					   (int)(p[i+i] + (p[0] - p[i+i])/2), (int)(p[i+i+1] + (p[1] - p[i+i+1])/2));
				// ... plus an arrowhead towards the target
				g.fill(M.createArrowhead((int)p[0], (int)p[1], (int)p[i+i], (int)p[i+i+1], magnification));
			}
			final float r = radius[i];
			if (r > 0) {
				g.drawOval((int)(p[i+i] - r), (int)(p[i+i+1] -r), (int)Math.ceil(r+r), (int)Math.ceil(r+r));
			}
		}
		if (active) {
			for (int i=0; i<lids.length; i++) {
				if (lid == lids[i]) {
					// draw handle
					DisplayCanvas.drawHandle(g, (int)p[i+i], (int)p[i+i+1], magnification);
				}
			}
			if (lids[0] == lid) {
				// Draw an "O" next to the origin point
				Composite comp = g.getComposite();
				g.setColor(Color.white);
				g.setXORMode(Color.green);
				g.drawString("O", (int)(p[0] + (4.0 / magnification)), (int)p[1]); // displaced 4 screen pixels to the right
				g.setComposite(comp);
			}
		}

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	static public void exportDTD(StringBuffer sb_header, HashSet hs, String indent) {
		String type = "t2_connector";
		if (hs.contains(type)) return;
		hs.add(type);
		sb_header.append(indent).append("<!ELEMENT t2_connector (").append(Displayable.commonDTDChildren()).append(")>\n");
		Displayable.exportDTD(type, sb_header, hs, indent);
		sb_header.append(indent).append(TAG_ATTR1).append(type).append(" origin").append(TAG_ATTR2)
			 .append(indent).append(TAG_ATTR1).append(type).append(" targets").append(TAG_ATTR2)
		;
	}

	synchronized public void exportXML(StringBuffer sb_body, String indent, Object any) {
		sb_body.append(indent).append("<t2_connector\n");
		String in = indent + "\t";
		super.exportXML(sb_body, in, any);
		String[] RGB = Utils.getHexRGBColor(color);
		sb_body.append(in).append("style=\"fill:none;stroke-opacity:").append(alpha).append(";stroke:#").append(RGB[0]).append(RGB[1]).append(RGB[2]).append(";stroke-width:1.0px;stroke-opacity:1.0\"\n");
		if (null != p) {
			sb_body.append(in).append("origin=\"").append(p[0]).append(',').append(p[1]).append(',').append(lids[0]).append(',').append(radius[0]).append("\"\n");
			if (lids.length > 0) {
				sb_body.append(in).append("targets=\"");
				for (int i=1; i<lids.length; i++) {
					sb_body.append(p[i+i]).append(',').append(p[i+i+1]).append(',').append(lids[i]).append(',').append(radius[i]).append(',');
				}
				sb_body.setLength(sb_body.length()-1); // remove last comma
				sb_body.append("\"\n");
			}
		}
		sb_body.append(indent).append(">\n");
		super.restXML(sb_body, in, any);
		sb_body.append(indent).append("</t2_connector>\n");
	}


	public Connector clone(final Project pr, final boolean copy_id) {
		final long nid = copy_id ? this.id : pr.getLoader().getNextId();
		Connector copy = new Connector(pr, nid, title, this.alpha, true, this.color, this.locked, this.at);
		copy.lids = null == this.lids ? null : this.lids.clone();
		copy.p = null == this.p ? null : this.p.clone();
		copy.radius = null == this.radius ? null : this.radius.clone();
		return copy;
	}

	public boolean isDeletable() {
		return null == p;
	}

	@Override
	final Class getInternalDataPackageClass() {
		return DPConnector.class;
	}

	@Override
	synchronized Object getDataPackage() {
		return new DPConnector(this);
	}

	static private final class DPConnector extends Displayable.DataPackage {
		final float[] p, radius;
		final long[] lids;
		DPConnector(final Connector con) {
			super(con);
			if (null == con.p) {
				this.p = null;
				this.lids = null;
				this.radius = null;
			} else {
				this.p = con.p.clone();
				this.lids = con.lids.clone();
				this.radius = con.radius.clone();
			}
		}
		@Override
		final boolean to2(final Displayable d) {
			super.to1(d);
			final Connector con = (Connector)d;
			con.p = this.p.clone();
			con.lids = this.lids.clone();
			con.radius = this.radius.clone();
			return true;
		}
	}

	public ResultsTable measure(ResultsTable rt) {
		if (null == p) return rt;
		if (null == rt) rt = Utils.createResultsTable("Connector results", new String[]{"id", "index", "x", "y", "z", "radius"});
		float[] p = transformPoints(this.p);
		final Calibration cal = layer_set.getCalibration();
		for (int i=0; i<lids.length; i++) {
			rt.incrementCounter();
			rt.addLabel("units", cal.getUnit());
			rt.addValue(0, this.id);
			rt.addValue(1, i); // start at 0, the origin
			rt.addValue(2, p[i+i] * cal.pixelWidth);
			rt.addValue(3, p[i+i+1] * cal.pixelHeight);
			rt.addValue(4, layer_set.getLayer(lids[i]).getZ() * cal.pixelWidth);
			rt.addValue(5, radius[i] * cal.pixelWidth);
		}
		return rt;
	}

	public String getInfo() {
		if (null == p) return "Empty";
		return new StringBuilder("Targets: ").append(lids.length-1).append('\n').toString();
	}

	/** Temporary while waiting for multi-meshes: create an icosphere with subdividion two, and a tube of half the target diameter from the origin to the targets. */
	public List generateTriangles(double scale, int resample) {

		if (null == p || 0 == p.length) return new ArrayList();

		// Create a temporary treeline:  (this is telling how much a Connector should just be a subclass of Tree)
		Treeline tl = new Treeline(project, -1, "", this.width, this.height, this.alpha, this.visible, this.color, this.locked, this.at);
		tl.layer_set = this.layer_set;
		tl.root = tl.newNode(p[0], p[1], layer_set.getLayer(lids[0]), null);
		float r = 0 == radius[0] ? 1 : radius[0];
		((Treeline.RadiusNode)tl.root).setData(r);
		tl.addNode(null, tl.root, Node.MAX_EDGE_CONFIDENCE);

		for (int i=1; i<lids.length; i++) {
			Treeline.RadiusNode nd = (Treeline.RadiusNode) tl.newNode(p[i+i], p[i+i+1], layer_set.getLayer(lids[i]), null);
			r = 0 == radius[i] ? 1 : radius[i];
			nd.setData(r);
			tl.addNode(tl.root, nd, Node.MAX_EDGE_CONFIDENCE);
		}

		return tl.generateMesh(scale, 12);
	}

	protected boolean layerRemoved(Layer la) {
		super.layerRemoved(la);
		if (null == p || 0 == lids.length) return true; // empty
		if (la.getId() == lids[0]) {
			return remove2(false);
		}
		for (int i=1; i<lids.length; i++) {
			if (la.getId() == lids[i]) {
				removeTarget(i);
				i--;
			}
		}
		return true;
	}

	public boolean contains(final Layer la, int x_p, int y_p) {
		if (null == p || 0 == lids.length) return false; // empty
		final long lid = la.getId();
		if (!this.at.isIdentity()) {
			Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_p = (int)po.x;
			y_p = (int)po.y;
		}
		for (int i=0,j=0; i<lids.length; i++,j+=2) {
			if (lid != lids[i]) continue;
			if ((float)(Math.pow(p[j] - x_p, 2) + Math.pow(p[j+1] - y_p, 2)) < radius[i]*radius[i]) return true;
		}
		return false;
	}

	synchronized public boolean apply(final Layer la, final Area roi, final mpicbg.models.CoordinateTransform ict) throws Exception {
		if (null == p || 0 == lids.length) return true; // empty
		float[] fp = null;
		mpicbg.models.CoordinateTransform chain = null;
		Area localroi = null;
		AffineTransform inverse = null;
		for (int i=0; i<lids.length; i++) {
			if (la.getId() == lids[i]) {
				if (null == localroi) {
					inverse = this.at.createInverse();
					localroi = roi.createTransformedArea(inverse);
				}
				if (localroi.contains(p[i+i], p[i+i+1])) {
					if (null == chain) {
						chain = M.wrap(this.at, ict, inverse);
						fp = new float[2];
					}
					// Keep point copy
					float ox = p[i+i],
					      oy = p[i+i+1];
					// Do the point
					fp[0] = p[i+i];
					fp[1] = p[i+i+1];
					chain.applyInPlace(fp);
					p[i+i] = fp[0];
					p[i+i+1] = fp[1];
					// Transform radius by considering it a point to the right of the actual point
					fp[0] = ox + radius[i];
					fp[1] = oy;
					chain.applyInPlace(fp);
					radius[i] = Math.abs(p[i+i] - fp[0]);
				}
			}
		}
		calculateBoundingBox();
		return true;
	}
	synchronized public boolean apply(final VectorDataTransform vdt) throws Exception {
		if (null == p || 0 == lids.length) return true; // empty
		final float[] fp = new float[2];
		final VectorDataTransform vlocal = vdt.makeLocalTo(this);
		for (int i=0; i<lids.length; i++) {
			if (vdt.layer.getId() == lids[i]) {
				for (final VectorDataTransform.ROITransform rt : vlocal.transforms) {
					if (rt.roi.contains(p[i+i], p[i+i+1])) {
						// Keep point copy
						float ox = p[i+i],
						      oy = p[i+i+1];
						// Transform the point
						fp[0] = p[i+i];
						fp[1] = p[i+i+1];
						rt.ct.applyInPlace(fp);
						p[i+i] = fp[0];
						p[i+i+1] = fp[1];
						// Transform radius by considering it a point to the right of the untransformed point
						fp[0] = ox + radius[i];
						fp[1] = oy;
						rt.ct.applyInPlace(fp);
						radius[i] = Math.abs(p[i+i] - fp[0]);
						break;
					}
				}
			}
		}
		calculateBoundingBox();
		return true;
	}

	@Override
	public Collection<Long> getLayerIds() {
		return Utils.asList(lids);
	}

	@Override
	synchronized public Area getAreaAt(final Layer layer) {
		final Area a = new Area();
		if (null == lids) return a;
		for (int i=0; i<lids.length; i++) {
			if (lids[i] != layer.getId()) continue;
			a.add(new Area(new Ellipse2D.Float(p[i+i] - radius[i], p[i+i+1] - radius[i], radius[i], radius[i])));
		}
		a.transform(this.at);
		return a;
	}

	/** Takes the List of Connector instances and adds the targets of all to the first one.
	 *  Removes the others from the LayerSet and from the Project.
	 *  If any of the Connector instances cannot be removed, returns null. */
	static public Connector merge(final List<Connector> col) throws NoninvertibleTransformException {
		if (null == col || 0 == col.size()) return null;
		final Connector base = col.get(0);
		for (final Connector con : col.subList(1, col.size())) {
			base.mergeTargets(con);
			if (!con.remove2(false)) {
				Utils.log("FAILED to merge Connector " + con + " into " + base);
				return null;
			}
		}
		return base;
	}
}
