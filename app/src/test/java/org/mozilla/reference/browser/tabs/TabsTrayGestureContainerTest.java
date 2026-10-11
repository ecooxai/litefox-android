package org.mozilla.reference.browser.tabs;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.constraintlayout.widget.ConstraintLayout;
import kotlin.Unit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mozilla.reference.browser.R;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, application = Application.class, qualifiers = "w400dp-h800dp-mdpi")
public class TabsTrayGestureContainerTest {
    private TabsTrayGestureContainer root;
    private int dismissals;

    @Before public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        root = new TabsTrayGestureContainer(activity);
        activity.setContentView(root);
        FrameLayout panel = new FrameLayout(activity);
        panel.setId(R.id.tabsTrayPanel);
        root.addView(panel, new ConstraintLayout.LayoutParams(400,320));
        View toolbar = new View(activity);
        toolbar.setId(R.id.tabsToolbar);
        toolbar.setClickable(true);
        panel.addView(toolbar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,56));
        root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,400,800);
        panel.layout(0,350,400,670);
        toolbar.layout(0,240,400,296); // Toolbar is y=590..646 in the root, not y=240..296.
        root.setOnSwipeDownFromBottomBar(() -> { dismissals++; return Unit.INSTANCE; });
    }

    private void event(int action, float x, float y) {
        MotionEvent e=MotionEvent.obtain(0,100,action,x,y,0);
        root.dispatchTouchEvent(e);
        e.recycle();
    }
    private void drain() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    @Test public void listScrollNeverDismisses() {
        event(MotionEvent.ACTION_DOWN,200,450);
        event(MotionEvent.ACTION_MOVE,200,750);
        event(MotionEvent.ACTION_UP,200,750);
        drain(); assertEquals(0,dismissals);
    }
    @Test public void lastListRowIsNotADismissHandle() {
        event(MotionEvent.ACTION_DOWN,200,585);
        event(MotionEvent.ACTION_MOVE,200,750);
        event(MotionEvent.ACTION_UP,200,750);
        drain(); assertEquals(0,dismissals);
    }
    @Test public void toolbarDragUsesNestedParentCoordinates() {
        event(MotionEvent.ACTION_DOWN,200,610);
        event(MotionEvent.ACTION_MOVE,200,750);
        event(MotionEvent.ACTION_UP,200,750);
        drain(); assertEquals(1,dismissals);
    }
    @Test public void toolbarTapDoesNotSwipeDismiss() {
        event(MotionEvent.ACTION_DOWN,200,610);
        event(MotionEvent.ACTION_UP,200,610);
        drain(); assertEquals(0,dismissals);
    }
    @Test public void horizontalToolbarSwipeCannotBecomeDownwardDismissal() {
        event(MotionEvent.ACTION_DOWN,200,610);
        event(MotionEvent.ACTION_MOVE,360,620);
        event(MotionEvent.ACTION_MOVE,360,760);
        event(MotionEvent.ACTION_UP,360,760);
        drain(); assertEquals(0,dismissals);
    }
    @Test public void upwardToolbarSwipeCannotBecomeDownwardDismissal() {
        event(MotionEvent.ACTION_DOWN,200,610);
        event(MotionEvent.ACTION_MOVE,200,550);
        event(MotionEvent.ACTION_MOVE,200,750);
        event(MotionEvent.ACTION_UP,200,750);
        drain(); assertEquals(0,dismissals);
    }
}
