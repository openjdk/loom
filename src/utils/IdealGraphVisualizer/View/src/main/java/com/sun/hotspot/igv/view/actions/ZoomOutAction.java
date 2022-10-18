/*
 * Copyright (c) 2008, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */
package com.sun.hotspot.igv.view.actions;

import com.sun.hotspot.igv.view.EditorTopComponent;
import javax.swing.Action;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.util.HelpCtx;
import org.openide.util.ImageUtilities;
import org.openide.util.NbBundle;
import org.openide.util.NbBundle.Messages;
import org.openide.util.actions.CallableSystemAction;

/**
 * @author Thomas Wuerthinger
 */
@ActionID(category = "View", id = "com.sun.hotspot.igv.view.actions.ZoomOutAction")
@ActionRegistration(displayName = "#CTL_ZoomOutAction")
@ActionReferences({
        @ActionReference(path = "Menu/View", position = 500),
        @ActionReference(path = "Shortcuts", name = "D-MINUS")
})
@Messages({
        "CTL_ZoomOutAction=Zoom out",
        "HINT_ZoomOutAction=Zoom out of the graph"
})
public final class ZoomOutAction extends CallableSystemAction {

    public ZoomOutAction() {
        putValue(Action.SHORT_DESCRIPTION, getDescription());
        putValue(Action.SMALL_ICON , ImageUtilities.loadImageIcon(iconResource(), true));
    }

    @Override
    public void performAction() {
        EditorTopComponent editor = EditorTopComponent.getActive();
        if (editor != null) {
            editor.zoomOut();
        }
    }

    @Override
    public String getName() {
        return NbBundle.getMessage(NextDiagramAction.class, "CTL_ZoomOutAction");
    }

    private String getDescription() {
        return NbBundle.getMessage(NextDiagramAction.class, "HINT_ZoomOutAction");
    }

    @Override
    public HelpCtx getHelpCtx() {
        return HelpCtx.DEFAULT_HELP;
    }

    @Override
    protected boolean asynchronous() {
        return false;
    }

    @Override
    protected String iconResource() {
        return "com/sun/hotspot/igv/view/images/zoomOut.svg"; // NOI18N
    }
}
