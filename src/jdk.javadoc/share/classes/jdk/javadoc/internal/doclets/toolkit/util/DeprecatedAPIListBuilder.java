/*
 * Copyright (c) 1998, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */

package jdk.javadoc.internal.doclets.toolkit.util;

import java.util.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

import jdk.javadoc.internal.doclets.toolkit.BaseConfiguration;

/**
 * Build list of all the deprecated packages, classes, constructors, fields and methods.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class DeprecatedAPIListBuilder {
    /**
     * List of deprecated type Lists.
     */
    private final Map<DeprElementKind, SortedSet<Element>> deprecatedMap;
    private final BaseConfiguration configuration;
    private final Utils utils;

    public enum DeprElementKind {
        REMOVAL,
        MODULE,
        PACKAGE,
        INTERFACE,
        CLASS,
        ENUM,
        EXCEPTION,              // no ElementKind mapping
        ERROR,                  // no ElementKind mapping
        ANNOTATION_TYPE,
        FIELD,
        METHOD,
        CONSTRUCTOR,
        ENUM_CONSTANT,
        ANNOTATION_TYPE_MEMBER // no ElementKind mapping
    };

    /**
     * Constructor.
     *
     * @param configuration the current configuration of the doclet
     */
    public DeprecatedAPIListBuilder(BaseConfiguration configuration) {
        this.configuration = configuration;
        this.utils = configuration.utils;
        deprecatedMap = new EnumMap<>(DeprElementKind.class);
        for (DeprElementKind kind : DeprElementKind.values()) {
            deprecatedMap.put(kind,
                    new TreeSet<>(utils.comparators.makeDeprecatedComparator()));
        }
        buildDeprecatedAPIInfo();
    }

    public boolean isEmpty() {
        return deprecatedMap.values().stream().allMatch(Set::isEmpty);
    }

    /**
     * Build the sorted list of all the deprecated APIs in this run.
     * Build separate lists for deprecated modules, packages, classes, constructors,
     * methods and fields.
     */
    private void buildDeprecatedAPIInfo() {
        SortedSet<Element> rset = deprecatedMap.get(DeprElementKind.REMOVAL);
        SortedSet<ModuleElement> modules = configuration.modules;
        SortedSet<Element> mset = deprecatedMap.get(DeprElementKind.MODULE);
        for (Element me : modules) {
            if (utils.isDeprecatedForRemoval(me)) {
                rset.add(me);
            }
            if (utils.isDeprecated(me)) {
                mset.add(me);
            }
        }
        SortedSet<PackageElement> packages = configuration.packages;
        SortedSet<Element> pset = deprecatedMap.get(DeprElementKind.PACKAGE);
        for (Element pe : packages) {
            if (utils.isDeprecatedForRemoval(pe)) {
                rset.add(pe);
            }
            if (utils.isDeprecated(pe)) {
                pset.add(pe);
            }
        }
        for (Element e : configuration.getIncludedTypeElements()) {
            TypeElement te = (TypeElement)e;
            SortedSet<Element> eset;
            if (utils.isDeprecatedForRemoval(e)) {
                rset.add(e);
            }
            if (utils.isDeprecated(e)) {
                switch (e.getKind()) {
                    case ANNOTATION_TYPE:
                        eset = deprecatedMap.get(DeprElementKind.ANNOTATION_TYPE);
                        eset.add(e);
                        break;
                    case CLASS:
                        if (utils.isError(te)) {
                            eset = deprecatedMap.get(DeprElementKind.ERROR);
                        } else if (utils.isException(te)) {
                            eset = deprecatedMap.get(DeprElementKind.EXCEPTION);
                        } else {
                            eset = deprecatedMap.get(DeprElementKind.CLASS);
                        }
                        eset.add(e);
                        break;
                    case INTERFACE:
                        eset = deprecatedMap.get(DeprElementKind.INTERFACE);
                        eset.add(e);
                        break;
                    case ENUM:
                        eset = deprecatedMap.get(DeprElementKind.ENUM);
                        eset.add(e);
                        break;
                }
            }
            composeDeprecatedList(rset, deprecatedMap.get(DeprElementKind.FIELD),
                    utils.getFields(te));
            composeDeprecatedList(rset, deprecatedMap.get(DeprElementKind.METHOD),
                    utils.getMethods(te));
            composeDeprecatedList(rset, deprecatedMap.get(DeprElementKind.CONSTRUCTOR),
                    utils.getConstructors(te));
            if (utils.isEnum(e)) {
                composeDeprecatedList(rset, deprecatedMap.get(DeprElementKind.ENUM_CONSTANT),
                        utils.getEnumConstants(te));
            }
            if (utils.isAnnotationType(e)) {
                composeDeprecatedList(rset, deprecatedMap.get(DeprElementKind.ANNOTATION_TYPE_MEMBER),
                        utils.getAnnotationMembers(te));

            }
        }
    }

    /**
     * Add the members into a single list of deprecated members.
     *
     * @param rset set of elements deprecated for removal.
     * @param sset set of deprecated elements.
     * @param members members to be added in the list.
     */
    private void composeDeprecatedList(SortedSet<Element> rset, SortedSet<Element> sset, List<? extends Element> members) {
        for (Element member : members) {
            if (utils.isDeprecatedForRemoval(member)) {
                rset.add(member);
            }
            if (utils.isDeprecated(member)) {
                sset.add(member);
            }
        }
    }

    /**
     * Return the list of deprecated elements of a given type.
     *
     * @param kind the DeprElementKind
     * @return
     */
    public SortedSet<Element> getSet(DeprElementKind kind) {
        return deprecatedMap.get(kind);
    }

    /**
     * Return true if the list of a given type has size greater than 0.
     *
     * @param kind the type of list being checked.
     */
    public boolean hasDocumentation(DeprElementKind kind) {
        return !deprecatedMap.get(kind).isEmpty();
    }
}
