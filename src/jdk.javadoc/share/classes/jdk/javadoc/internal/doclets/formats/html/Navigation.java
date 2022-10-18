/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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

package jdk.javadoc.internal.doclets.formats.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;

import jdk.javadoc.internal.doclets.formats.html.markup.ContentBuilder;
import jdk.javadoc.internal.doclets.formats.html.markup.Entity;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlAttr;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlStyle;
import jdk.javadoc.internal.doclets.formats.html.markup.TagName;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTree;
import jdk.javadoc.internal.doclets.formats.html.markup.Links;
import jdk.javadoc.internal.doclets.formats.html.markup.Text;
import jdk.javadoc.internal.doclets.toolkit.Content;
import jdk.javadoc.internal.doclets.toolkit.util.DocFile;
import jdk.javadoc.internal.doclets.toolkit.util.DocLink;
import jdk.javadoc.internal.doclets.toolkit.util.DocPath;
import jdk.javadoc.internal.doclets.toolkit.util.DocPaths;
import jdk.javadoc.internal.doclets.toolkit.util.VisibleMemberTable;

import static jdk.javadoc.internal.doclets.toolkit.util.VisibleMemberTable.Kind.*;

/**
 * Factory for navigation bar.
 *
 * <p>
 * <b>This is NOT part of any supported API. If you write code that depends on this, you do so at
 * your own risk. This code and its internal interfaces are subject to change or deletion without
 * notice.</b>
 */
public class Navigation {

    private final HtmlConfiguration configuration;
    private final HtmlOptions options;
    private final Element element;
    private final Contents contents;
    private final HtmlIds htmlIds;
    private final DocPath path;
    private final DocPath pathToRoot;
    private final Links links;
    private final PageMode documentedPage;
    private Content navLinkModule;
    private Content navLinkPackage;
    private Content navLinkClass;
    private Content userHeader;
    private final String rowListTitle;
    private final Content searchLabel;
    private final String searchPlaceholder;
    private SubNavLinks subNavLinks;

    public enum PageMode {
        ALL_CLASSES,
        ALL_PACKAGES,
        CLASS,
        CONSTANT_VALUES,
        DEPRECATED,
        DOC_FILE,
        EXTERNAL_SPECS,
        HELP,
        INDEX,
        MODULE,
        NEW,
        OVERVIEW,
        PACKAGE,
        PREVIEW,
        SERIALIZED_FORM,
        SEARCH,
        SYSTEM_PROPERTIES,
        TREE,
        USE;
    }

    /**
     * An interface to provide links for the subnavigation area.
     */
    public interface SubNavLinks {
        /**
         * {@return a list of links to display in the subnavigation area}
         * Links should be wrapped in {@code HtmlTree.LI} elements as they are
         * displayed within an unordered list.
         */
        List<Content> getSubNavLinks();
    }

    /**
     * Creates a {@code Navigation} object for a specific file, to be written in a specific HTML
     * version.
     *
     * @param element element being documented. null if its not an element documentation page
     * @param configuration the configuration object
     * @param page the kind of page being documented
     * @param path the DocPath object
     */
    public Navigation(Element element, HtmlConfiguration configuration, PageMode page, DocPath path) {
        this.configuration = configuration;
        this.options = configuration.getOptions();
        this.element = element;
        this.contents = configuration.getContents();
        this.htmlIds = configuration.htmlIds;
        this.documentedPage = page;
        this.path = path;
        this.pathToRoot = path.parent().invert();
        this.links = new Links(path);
        this.rowListTitle = configuration.getDocResources().getText("doclet.Navigation");
        this.searchLabel = contents.getContent("doclet.search");
        this.searchPlaceholder = configuration.getDocResources().getText("doclet.search_placeholder");
    }

    public Navigation setNavLinkModule(Content navLinkModule) {
        this.navLinkModule = navLinkModule;
        return this;
    }

    public Navigation setNavLinkPackage(Content navLinkPackage) {
        this.navLinkPackage = navLinkPackage;
        return this;
    }

    public Navigation setNavLinkClass(Content navLinkClass) {
        this.navLinkClass = navLinkClass;
        return this;
    }

    public Navigation setUserHeader(Content userHeader) {
        this.userHeader = userHeader;
        return this;
    }

    public Navigation setSubNavLinks(SubNavLinks subNavLinks) {
        this.subNavLinks = subNavLinks;
        return this;
    }

    /**
     * Adds the links for the main navigation.
     *
     * @param target the content to which the main navigation will added
     */
    private void addMainNavLinks(Content target) {
        switch (documentedPage) {
            case OVERVIEW:
                addActivePageLink(target, contents.overviewLabel, options.createOverview());
                addModuleLink(target);
                addPackageLink(target);
                addPageLabel(target, contents.classLabel, true);
                addPageLabel(target, contents.useLabel, options.classUse());
                addTreeLink(target);
                addPreviewLink(target);
                addNewLink(target);
                addDeprecatedLink(target);
                addIndexLink(target);
                addHelpLink(target);
                break;
            case MODULE:
                addOverviewLink(target);
                addActivePageLink(target, contents.moduleLabel, configuration.showModules);
                addPackageLink(target);
                addPageLabel(target, contents.classLabel, true);
                addPageLabel(target, contents.useLabel, options.classUse());
                addTreeLink(target);
                addPreviewLink(target);
                addNewLink(target);
                addDeprecatedLink(target);
                addIndexLink(target);
                addHelpLink(target);
                break;
            case PACKAGE:
                addOverviewLink(target);
                addModuleOfElementLink(target);
                addActivePageLink(target, contents.packageLabel, true);
                addPageLabel(target, contents.classLabel, true);
                if (options.classUse()) {
                    addItemToList(target, links.createLink(DocPaths.PACKAGE_USE,
                            contents.useLabel, ""));
                }
                if (options.createTree()) {
                    addItemToList(target, links.createLink(DocPaths.PACKAGE_TREE,
                            contents.treeLabel, ""));
                }
                addPreviewLink(target);
                addNewLink(target);
                addDeprecatedLink(target);
                addIndexLink(target);
                addHelpLink(target);
                break;
            case CLASS:
                addOverviewLink(target);
                addModuleOfElementLink(target);
                addPackageSummaryLink(target);
                addActivePageLink(target, contents.classLabel, true);
                if (options.classUse()) {
                    addItemToList(target, links.createLink(DocPaths.CLASS_USE.resolve(path.basename()),
                            contents.useLabel));
                }
                if (options.createTree()) {
                    addItemToList(target, links.createLink(DocPaths.PACKAGE_TREE,
                            contents.treeLabel, ""));
                }
                addPreviewLink(target);
                addNewLink(target);
                addDeprecatedLink(target);
                addIndexLink(target);
                addHelpLink(target);
                break;
            case USE:
                addOverviewLink(target);
                addModuleOfElementLink(target);
                if (element instanceof PackageElement) {
                    addPackageSummaryLink(target);
                    addPageLabel(target, contents.classLabel, true);
                } else {
                    addPackageOfElementLink(target);
                    addItemToList(target, navLinkClass);
                }
                addActivePageLink(target, contents.useLabel, options.classUse());
                if (element instanceof PackageElement) {
                    addItemToList(target, links.createLink(DocPaths.PACKAGE_TREE, contents.treeLabel));
                } else {
                    addItemToList(target, configuration.utils.isEnclosingPackageIncluded((TypeElement) element)
                            ? links.createLink(DocPath.parent.resolve(DocPaths.PACKAGE_TREE), contents.treeLabel)
                            : links.createLink(pathToRoot.resolve(DocPaths.OVERVIEW_TREE), contents.treeLabel));
                }
                addPreviewLink(target);
                addNewLink(target);
                addDeprecatedLink(target);
                addIndexLink(target);
                addHelpLink(target);
                break;
            case TREE:
                addOverviewLink(target);
                if (element == null) {
                    addPageLabel(target, contents.moduleLabel, configuration.showModules);
                    addPageLabel(target, contents.packageLabel, true);
                } else {
                    addModuleOfElementLink(target);
                    addPackageSummaryLink(target);
                }
                addPageLabel(target, contents.classLabel, true);
                addPageLabel(target, contents.useLabel, options.classUse());
                addActivePageLink(target, contents.treeLabel, options.createTree());
                addPreviewLink(target);
                addNewLink(target);
                addDeprecatedLink(target);
                addIndexLink(target);
                addHelpLink(target);
                break;
            case DEPRECATED:
            case INDEX:
            case HELP:
            case PREVIEW:
            case NEW:
                addOverviewLink(target);
                addModuleLink(target);
                addPackageLink(target);
                addPageLabel(target, contents.classLabel, true);
                addPageLabel(target, contents.useLabel, options.classUse());
                addTreeLink(target);
                if (documentedPage == PageMode.PREVIEW) {
                    addActivePageLink(target, contents.previewLabel,
                            configuration.conditionalPages.contains(HtmlConfiguration.ConditionalPage.PREVIEW));
                } else {
                    addPreviewLink(target);
                }
                if (documentedPage == PageMode.NEW) {
                    addActivePageLink(target, contents.newLabel,
                            configuration.conditionalPages.contains(HtmlConfiguration.ConditionalPage.NEW));
                } else {
                    addNewLink(target);
                }
                if (documentedPage == PageMode.DEPRECATED) {
                    addActivePageLink(target, contents.deprecatedLabel,
                            configuration.conditionalPages.contains(HtmlConfiguration.ConditionalPage.DEPRECATED));
                } else {
                    addDeprecatedLink(target);
                }
                if (documentedPage == PageMode.INDEX) {
                    addActivePageLink(target, contents.indexLabel, options.createIndex());
                } else {
                    addIndexLink(target);
                }
                if (documentedPage == PageMode.HELP) {
                    addActivePageLink(target, contents.helpLabel, !options.noHelp());
                } else {
                    addHelpLink(target);
                }
                break;
            case ALL_CLASSES:
            case ALL_PACKAGES:
            case CONSTANT_VALUES:
            case EXTERNAL_SPECS:
            case SERIALIZED_FORM:
            case SEARCH:
            case SYSTEM_PROPERTIES:
                addOverviewLink(target);
                addModuleLink(target);
                addPackageLink(target);
                addPageLabel(target, contents.classLabel, true);
                addPageLabel(target, contents.useLabel, options.classUse());
                addTreeLink(target);
                addPreviewLink(target);
                addNewLink(target);
                addDeprecatedLink(target);
                addIndexLink(target);
                addHelpLink(target);
                break;
            case DOC_FILE:
                addOverviewLink(target);
                addModuleOfElementLink(target);
                addItemToList(target, navLinkPackage);
                addPageLabel(target, contents.classLabel, true);
                addPageLabel(target, contents.useLabel, options.classUse());
                addTreeLink(target);
                addPreviewLink(target);
                addNewLink(target);
                addDeprecatedLink(target);
                addIndexLink(target);
                addHelpLink(target);
                break;
            default:
                break;
        }
    }

    /**
     * Adds the summary links to the subnavigation.
     *
     * @param target the content to which the subnavigation will be added
     * @param nested whether to create a flat or nested list
     */
    private void addSummaryLinks(Content target, boolean nested) {
        switch (documentedPage) {
            case MODULE, PACKAGE, CLASS, HELP -> {
                List<? extends Content> listContents = subNavLinks.getSubNavLinks()
                        .stream().map(HtmlTree::LI).toList();
                if (!listContents.isEmpty()) {
                Content label = switch (documentedPage) {
                        case MODULE -> contents.moduleSubNavLabel;
                        case PACKAGE -> contents.packageSubNavLabel;
                        case CLASS -> contents.summaryLabel;
                        case HELP -> contents.helpSubNavLabel;
                        default -> Text.EMPTY;
                    };
                    if (nested) {
                        target.add(HtmlTree.LI(HtmlTree.P(label))
                                .add(new HtmlTree(TagName.UL).add(listContents)));
                    } else {
                        target.add(HtmlTree.LI(label).add(Entity.NO_BREAK_SPACE));
                        addListToNav(listContents, target);
                    }
                }
            }
        }
    }

    /**
     * Adds the detail links to subnavigation.
     *
     * @param target the content to which the links will be added
     * @param nested whether to create a flat or nested list
     */
    private void addDetailLinks(Content target, boolean nested) {
        if (documentedPage == PageMode.CLASS) {
            List<Content> listContents = new ArrayList<>();
            VisibleMemberTable vmt = configuration.getVisibleMemberTable((TypeElement) element);
            Set<VisibleMemberTable.Kind> detailSet = VisibleMemberTable.Kind.forDetailsOf(element.getKind());
            for (VisibleMemberTable.Kind kind : detailSet) {
                addTypeDetailLink(kind, !vmt.getVisibleMembers(kind).isEmpty(), listContents);
            }
            if (!listContents.isEmpty()) {
                if (nested) {
                    var li = HtmlTree.LI(HtmlTree.P(contents.detailLabel));
                    li.add(new HtmlTree(TagName.UL).add(listContents));
                    target.add(li);
                } else {
                    var li = HtmlTree.LI(contents.detailLabel);
                    li.add(Entity.NO_BREAK_SPACE);
                    target.add(li);
                    addListToNav(listContents, target);
                }
            }
        }
    }

    /**
     * Adds the navigation Type detail link.
     *
     * @param kind the kind of member being documented
     * @param link true if the members are listed and need to be linked
     * @param listContents the list of contents to which the detail will be added.
     */
    protected void addTypeDetailLink(VisibleMemberTable.Kind kind, boolean link, List<Content> listContents) {
        addContentToList(listContents, switch (kind) {
            case CONSTRUCTORS -> links.createLink(HtmlIds.CONSTRUCTOR_DETAIL, contents.navConstructor, link);
            case ENUM_CONSTANTS -> links.createLink(HtmlIds.ENUM_CONSTANT_DETAIL, contents.navEnum, link);
            case FIELDS -> links.createLink(HtmlIds.FIELD_DETAIL, contents.navField, link);
            case METHODS -> links.createLink(HtmlIds.METHOD_DETAIL, contents.navMethod, link);
            case PROPERTIES -> links.createLink(HtmlIds.PROPERTY_DETAIL, contents.navProperty, link);
            case ANNOTATION_TYPE_MEMBER -> links.createLink(HtmlIds.ANNOTATION_TYPE_ELEMENT_DETAIL,
                    contents.navAnnotationTypeMember, link);
            default -> Text.EMPTY;
        });
    }

    private void addContentToList(List<Content> listContents, Content source) {
        listContents.add(HtmlTree.LI(source));
    }

    private void addItemToList(Content list, Content item) {
        list.add(HtmlTree.LI(item));
    }

    private void addListToNav(List<? extends Content> listContents, Content target) {
        int count = 0;
        for (Content liContent : listContents) {
            if (count < listContents.size() - 1) {
                liContent.add(Entity.NO_BREAK_SPACE);
                liContent.add("|");
                liContent.add(Entity.NO_BREAK_SPACE);
            }
            target.add(liContent);
            count++;
        }
    }

    private void addActivePageLink(Content target, Content label, boolean display) {
        if (display) {
            target.add(HtmlTree.LI(HtmlStyle.navBarCell1Rev, label));
        }
    }

    private void addPageLabel(Content target, Content label, boolean display) {
        if (display) {
            target.add(HtmlTree.LI(label));
        }
    }

    private void addOverviewLink(Content target) {
        if (options.createOverview()) {
            target.add(HtmlTree.LI(links.createLink(pathToRoot.resolve(DocPaths.INDEX),
                    contents.overviewLabel, "")));
        }
    }

    private void addModuleLink(Content target) {
        if (configuration.showModules) {
            if (configuration.modules.size() == 1) {
                ModuleElement mdle = configuration.modules.first();
                boolean included = configuration.utils.isIncluded(mdle);
                target.add(HtmlTree.LI((included)
                        ? links.createLink(pathToRoot.resolve(configuration.docPaths.moduleSummary(mdle)), contents.moduleLabel, "")
                        : contents.moduleLabel));
            } else if (!configuration.modules.isEmpty()) {
                addPageLabel(target, contents.moduleLabel, true);
            }
        }
    }

    private void addModuleOfElementLink(Content target) {
        if (configuration.showModules) {
            target.add(HtmlTree.LI(navLinkModule));
        }
    }

    private void addPackageLink(Content target) {
        if (configuration.packages.size() == 1) {
            PackageElement packageElement = configuration.packages.first();
            boolean included = packageElement != null && configuration.utils.isIncluded(packageElement);
            if (!included) {
                for (PackageElement p : configuration.packages) {
                    if (p.equals(packageElement)) {
                        included = true;
                        break;
                    }
                }
            }
            if (included || packageElement == null) {
                target.add(HtmlTree.LI(links.createLink(
                        pathToRoot.resolve(configuration.docPaths.forPackage(packageElement).resolve(DocPaths.PACKAGE_SUMMARY)),
                        contents.packageLabel)));
            } else {
                DocLink crossPkgLink = configuration.extern.getExternalLink(
                        packageElement, pathToRoot, DocPaths.PACKAGE_SUMMARY.getPath());
                if (crossPkgLink != null) {
                    target.add(HtmlTree.LI(links.createLink(crossPkgLink, contents.packageLabel)));
                } else {
                    target.add(HtmlTree.LI(contents.packageLabel));
                }
            }
        } else if (!configuration.packages.isEmpty()) {
            addPageLabel(target, contents.packageLabel, true);
        }
    }

    private void addPackageOfElementLink(Content target) {
        target.add(HtmlTree.LI(links.createLink(DocPath.parent.resolve(DocPaths.PACKAGE_SUMMARY),
                contents.packageLabel)));
    }

    private void addPackageSummaryLink(Content target) {
        target.add(HtmlTree.LI(links.createLink(DocPaths.PACKAGE_SUMMARY, contents.packageLabel)));
    }

    private void addTreeLink(Content target) {
        if (options.createTree()) {
            List<PackageElement> packages = new ArrayList<>(configuration.getSpecifiedPackageElements());
            DocPath docPath = packages.size() == 1 && configuration.getSpecifiedTypeElements().isEmpty()
                    ? pathToRoot.resolve(configuration.docPaths.forPackage(packages.get(0)).resolve(DocPaths.PACKAGE_TREE))
                    : pathToRoot.resolve(DocPaths.OVERVIEW_TREE);
            target.add(HtmlTree.LI(links.createLink(docPath, contents.treeLabel, "")));
        }
    }

    private void addDeprecatedLink(Content target) {
        if (configuration.conditionalPages.contains(HtmlConfiguration.ConditionalPage.DEPRECATED)) {
            target.add(HtmlTree.LI(links.createLink(pathToRoot.resolve(DocPaths.DEPRECATED_LIST),
                    contents.deprecatedLabel, "")));
        }
    }

    private void addPreviewLink(Content target) {
        if (configuration.conditionalPages.contains(HtmlConfiguration.ConditionalPage.PREVIEW)) {
            target.add(HtmlTree.LI(links.createLink(pathToRoot.resolve(DocPaths.PREVIEW_LIST),
                    contents.previewLabel, "")));
        }
    }

    private void addNewLink(Content target) {
        if (configuration.conditionalPages.contains(HtmlConfiguration.ConditionalPage.NEW)) {
            target.add(HtmlTree.LI(links.createLink(pathToRoot.resolve(DocPaths.NEW_LIST),
                    contents.newLabel, "")));
        }
    }

    private void addIndexLink(Content target) {
        if (options.createIndex()) {
            target.add(HtmlTree.LI(links.createLink(pathToRoot.resolve(
                    (options.splitIndex()
                            ? DocPaths.INDEX_FILES.resolve(DocPaths.indexN(1))
                            : DocPaths.INDEX_ALL)),
                    contents.indexLabel, "")));
        }
    }

    private void addHelpLink(Content target) {
        if (!options.noHelp()) {
            String helpfile = options.helpFile();
            DocPath helpfilenm;
            if (helpfile.isEmpty()) {
                helpfilenm = DocPaths.HELP_DOC;
            } else {
                DocFile file = DocFile.createFileForInput(configuration, helpfile);
                helpfilenm = DocPath.create(file.getName());
            }
            target.add(HtmlTree.LI(links.createLink(
                    new DocLink(pathToRoot.resolve(helpfilenm), htmlIds.forPage(documentedPage).name()),
                    contents.helpLabel, "")));
        }
    }

    private void addSearch(Content target) {
        String reset = "reset";
        var inputText = HtmlTree.INPUT("text", HtmlIds.SEARCH_INPUT)
                .put(HtmlAttr.PLACEHOLDER, searchPlaceholder);
        var inputReset = HtmlTree.INPUT(reset, HtmlIds.RESET_BUTTON)
                .put(HtmlAttr.VALUE, reset);
        var searchDiv = HtmlTree.DIV(HtmlStyle.navListSearch,
                links.createLink(pathToRoot.resolve(DocPaths.SEARCH_PAGE),
                        searchLabel, ""));
        searchDiv.add(inputText);
        searchDiv.add(inputReset);
        target.add(searchDiv);
    }

    /**
     * Returns the navigation content.
     *
     * @return the navigation content
     */
    public Content getContent() {
        if (options.noNavbar()) {
            return new ContentBuilder();
        }
        var navigationBar = HtmlTree.NAV();

        var navDiv = new HtmlTree(TagName.DIV);
        Content skipNavLinks = contents.getContent("doclet.Skip_navigation_links");
        String toggleNavLinks = configuration.getDocResources().getText("doclet.Toggle_navigation_links");
        navigationBar.add(MarkerComments.START_OF_TOP_NAVBAR);
        // The mobile menu button uses three empty spans to produce its animated icon
        HtmlTree iconSpan = HtmlTree.SPAN(HtmlStyle.navBarToggleIcon).add(Entity.NO_BREAK_SPACE);
        navDiv.setStyle(HtmlStyle.topNav)
                .setId(HtmlIds.NAVBAR_TOP)
                .add(new HtmlTree(TagName.BUTTON).setId(HtmlIds.NAVBAR_TOGGLE_BUTTON)
                        .put(HtmlAttr.ARIA_CONTROLS, HtmlIds.NAVBAR_TOP.name())
                        .put(HtmlAttr.ARIA_EXPANDED, String.valueOf(false))
                        .put(HtmlAttr.ARIA_LABEL, toggleNavLinks)
                        .add(iconSpan)
                        .add(iconSpan)
                        .add(iconSpan))
                .add(HtmlTree.DIV(HtmlStyle.skipNav,
                        links.createLink(HtmlIds.SKIP_NAVBAR_TOP, skipNavLinks,
                                skipNavLinks.toString())));
        Content aboutContent = userHeader;
        boolean addSearch = options.createIndex() && documentedPage != PageMode.SEARCH;

        var aboutDiv = HtmlTree.DIV(HtmlStyle.aboutLanguage, aboutContent);
        navDiv.add(aboutDiv);
        var navList = new HtmlTree(TagName.UL)
                .setId(HtmlIds.NAVBAR_TOP_FIRSTROW)
                .setStyle(HtmlStyle.navList)
                .put(HtmlAttr.TITLE, rowListTitle);
        addMainNavLinks(navList);
        navDiv.add(navList);
        var ulNavSummaryRight = HtmlTree.UL(HtmlStyle.subNavListSmall);
        addSummaryLinks(ulNavSummaryRight, true);
        addDetailLinks(ulNavSummaryRight, true);
        navDiv.add(ulNavSummaryRight);
        navigationBar.add(navDiv);

        var subDiv = HtmlTree.DIV(HtmlStyle.subNav);

        var div = new HtmlTree(TagName.DIV).setId(HtmlIds.NAVBAR_SUB_LIST);
        // Add the summary links if present.
        var ulNavSummary = HtmlTree.UL(HtmlStyle.subNavList);
        addSummaryLinks(ulNavSummary, false);
        div.add(ulNavSummary);
        // Add the detail links if present.
        var ulNavDetail = HtmlTree.UL(HtmlStyle.subNavList);
        addDetailLinks(ulNavDetail, false);
        div.add(ulNavDetail);
        subDiv.add(div);

        if (addSearch) {
            addSearch(subDiv);
        }
        navigationBar.add(subDiv);

        navigationBar.add(MarkerComments.END_OF_TOP_NAVBAR);
        navigationBar.add(HtmlTree.SPAN(HtmlStyle.skipNav)
                .addUnchecked(Text.EMPTY)
                .setId(HtmlIds.SKIP_NAVBAR_TOP));

        return navigationBar;
    }
}
