/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.SpecTree;
import com.sun.source.util.DocTreePath;
import com.sun.source.util.TreePath;

import jdk.javadoc.internal.doclets.formats.html.Navigation.PageMode;
import jdk.javadoc.internal.doclets.formats.html.markup.BodyContents;
import jdk.javadoc.internal.doclets.formats.html.markup.ContentBuilder;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlStyle;
import jdk.javadoc.internal.doclets.formats.html.markup.HtmlTree;
import jdk.javadoc.internal.doclets.formats.html.markup.Text;
import jdk.javadoc.internal.doclets.toolkit.Content;
import jdk.javadoc.internal.doclets.toolkit.DocletElement;
import jdk.javadoc.internal.doclets.toolkit.OverviewElement;
import jdk.javadoc.internal.doclets.toolkit.util.DocFileIOException;
import jdk.javadoc.internal.doclets.toolkit.util.DocPath;
import jdk.javadoc.internal.doclets.toolkit.util.DocPaths;
import jdk.javadoc.internal.doclets.toolkit.util.IndexItem;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

/**
 * Generates the file with the summary of all the references to external specifications.
 */
public class ExternalSpecsWriter extends HtmlDocletWriter {

    private final Navigation navBar;

    /**
     * Cached contents of {@code <title>...</title>} tags of the HTML pages.
     */
    final Map<Element, String> titles = new WeakHashMap<>();

    /**
     * Constructs ExternalSpecsWriter object.
     *
     * @param configuration The current configuration
     * @param filename Path to the file which is getting generated.
     */
    public ExternalSpecsWriter(HtmlConfiguration configuration, DocPath filename) {
        super(configuration, filename);
        this.navBar = new Navigation(null, configuration, PageMode.EXTERNAL_SPECS, path);
    }

    public static void generate(HtmlConfiguration configuration) throws DocFileIOException {
        generate(configuration, DocPaths.EXTERNAL_SPECS);
    }

    private static void generate(HtmlConfiguration configuration, DocPath fileName) throws DocFileIOException {
        boolean hasExternalSpecs = configuration.mainIndex != null
                && !configuration.mainIndex.getItems(DocTree.Kind.SPEC).isEmpty();
        if (!hasExternalSpecs) {
            return;
        }
        ExternalSpecsWriter w = new ExternalSpecsWriter(configuration, fileName);
        w.buildExternalSpecsPage();
        configuration.conditionalPages.add(HtmlConfiguration.ConditionalPage.EXTERNAL_SPECS);
    }

    /**
     * Prints all the "external specs" to the file.
     */
    protected void buildExternalSpecsPage() throws DocFileIOException {
        checkUniqueItems();

        String title = resources.getText("doclet.External_Specifications");
        HtmlTree body = getBody(getWindowTitle(title));
        Content mainContent = new ContentBuilder();
        addExternalSpecs(mainContent);
        body.add(new BodyContents()
                .setHeader(getHeader(PageMode.EXTERNAL_SPECS))
                .addMainContent(HtmlTree.DIV(HtmlStyle.header,
                        HtmlTree.HEADING(Headings.PAGE_TITLE_HEADING,
                                contents.getContent("doclet.External_Specifications"))))
                .addMainContent(mainContent)
                .setFooter(getFooter()));
        printHtmlDocument(null, "external specifications", body);

        if (configuration.mainIndex != null) {
            configuration.mainIndex.add(IndexItem.of(IndexItem.Category.TAGS, title, path));
        }
    }

    protected void checkUniqueItems() {
        Map<String, Map<String, List<IndexItem>>> itemsByURL = new HashMap<>();
        Map<String, Map<String, List<IndexItem>>> itemsByTitle = new HashMap<>();
        for (IndexItem ii : configuration.mainIndex.getItems(DocTree.Kind.SPEC)) {
            if (ii.getDocTree() instanceof SpecTree st) {
                String url = st.getURL().toString();
                String title = st.getTitle().toString();
                itemsByTitle
                        .computeIfAbsent(title, l -> new HashMap<>())
                        .computeIfAbsent(url, u -> new ArrayList<>())
                        .add(ii);
                itemsByURL
                        .computeIfAbsent(url, u -> new HashMap<>())
                        .computeIfAbsent(title, l -> new ArrayList<>())
                        .add(ii);
            }
        }

        itemsByURL.forEach((url, title) -> {
            if (title.size() > 1) {
                messages.error("doclet.extSpec.spec.has.multiple.titles", url,
                        title.values().stream().distinct().count());
                title.forEach((t, list) ->
                        list.forEach(ii ->
                                report(ii, "doclet.extSpec.url.title", url, t)));
            }
        });

        itemsByTitle.forEach((title, urls) -> {
            if (urls.size() > 1) {
                messages.error("doclet.extSpec.title.for.multiple.specs", title,
                        urls.values().stream().distinct().count());
                urls.forEach((u, list) ->
                        list.forEach(ii ->
                                report(ii, "doclet.extSpec.title.url", title, u)));
            }
        });
    }

    private void report(IndexItem ii, String key, Object... args) {
        String message = messages.getResources().getText(key, args);
        Element e = ii.getElement();
        if (e == null) {
            configuration.reporter.print(Diagnostic.Kind.NOTE, message);
        } else {
            TreePath tp = utils.getTreePath(e);
            DocTreePath dtp = new DocTreePath(new DocTreePath(tp, utils.getDocCommentTree(e)), ii.getDocTree());
            configuration.reporter.print(Diagnostic.Kind.NOTE, dtp, message);
        }
    }

    /**
     * Adds all the references to external specifications to the content tree.
     *
     * @param content HtmlTree content to which the links will be added
     */
    protected void addExternalSpecs(Content content) {
        final int USE_DETAILS_THRESHHOLD = 20;
        Map<String, List<IndexItem>> searchIndexMap = groupExternalSpecs();
        Table table = new Table(HtmlStyle.summaryTable)
                .setCaption(contents.externalSpecifications)
                .setHeader(new TableHeader(contents.specificationLabel, contents.referencedIn))
                .setColumnStyles(HtmlStyle.colFirst, HtmlStyle.colLast);
        for (List<IndexItem> searchIndexItems : searchIndexMap.values()) {
            Content specName = createSpecLink(searchIndexItems.get(0));
            Content referencesList = HtmlTree.UL(HtmlStyle.refList, searchIndexItems,
                    item -> HtmlTree.LI(createLink(item)));
            Content references = searchIndexItems.size() < USE_DETAILS_THRESHHOLD
                    ? referencesList
                    : HtmlTree.DETAILS()
                            .add(HtmlTree.SUMMARY(contents.getContent("doclet.references",
                                    String.valueOf(searchIndexItems.size()))))
                            .add(referencesList);
            table.addRow(specName, references);
        }
        content.add(table);
    }

    private Map<String, List<IndexItem>> groupExternalSpecs() {
        return configuration.mainIndex.getItems(DocTree.Kind.SPEC).stream()
                .collect(groupingBy(IndexItem::getLabel, () -> new TreeMap<>(getTitleComparator()), toList()));
    }

    Comparator<String> getTitleComparator() {
        Collator collator = Collator.getInstance();
        return new Comparator<>() {
            @Override
            public int compare(String s1, String s2) {
                int i1 = 0;
                int i2 = 0;
                while (i1 < s1.length() && i2 < s2.length()) {
                    int j1 = find(s1, i1, Character::isDigit);
                    int j2 = find(s2, i2, Character::isDigit);
                    int cmp = collator.compare(s1.substring(i1, j1), s2.substring(i2, j2));
                    if (cmp != 0) {
                        return cmp;
                    }
                    if (j1 == s1.length() || j2 == s2.length()) {
                        i1 = j1;
                        i2 = j2;
                        break;
                    }
                    int k1 = find(s1, j1, ch -> !Character.isDigit(ch));
                    int k2 = find(s2, j2, ch -> !Character.isDigit(ch));
                    cmp = Integer.compare(Integer.parseInt(s1.substring(j1, k1)), Integer.parseInt(s2.substring(j2, k2)));
                    if (cmp != 0) {
                        return cmp;
                    }
                    i1 = k1;
                    i2 = k2;
                }
                return i1 < s1.length() ? 1 : i2 < s2.length() ? -1 : 0;
            }
        };
    }

    private static int find(String s, int start, Predicate<Character> p) {
        int i = start;
        while (i < s.length() && !p.test(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private Content createLink(IndexItem i) {
        assert i.getDocTree().getKind() == DocTree.Kind.SPEC : i;
        Element element = i.getElement();
        if (element instanceof OverviewElement) {
            return links.createLink(pathToRoot.resolve(i.getUrl()),
                    resources.getText("doclet.Overview"));
        } else if (element instanceof DocletElement) {
            DocletElement e = (DocletElement) element;
            // Implementations of DocletElement do not override equals and
            // hashCode; putting instances of DocletElement in a map is not
            // incorrect, but might well be inefficient
            String t = titles.computeIfAbsent(element, utils::getHTMLTitle);
            if (t.isBlank()) {
                // The user should probably be notified (a warning?) that this
                // file does not have a title
                Path p = Path.of(e.getFileObject().toUri());
                t = p.getFileName().toString();
            }
            ContentBuilder b = new ContentBuilder();
            b.add(HtmlTree.CODE(Text.of(i.getHolder() + ": ")));
            // non-program elements should be displayed using a normal font
            b.add(t);
            return links.createLink(pathToRoot.resolve(i.getUrl()), b);
        } else {
            // program elements should be displayed using a code font
            Content link = links.createLink(pathToRoot.resolve(i.getUrl()), i.getHolder());
            return HtmlTree.CODE(link);
        }
    }

    private Content createSpecLink(IndexItem i) {
        assert i.getDocTree().getKind() == DocTree.Kind.SPEC : i;
        SpecTree specTree = (SpecTree) i.getDocTree();

        Content title = Text.of(i.getLabel());

        URI specURI;
        try {
            specURI = new URI(specTree.getURL().getBody());
        } catch (URISyntaxException e) {
            // should not happen: items with bad URIs should not make it into the index
            return title;
        }

        return HtmlTree.A(resolveExternalSpecURI(specURI), title);
    }
}
