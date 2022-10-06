package org.opencds.cqf.cql.ls.server.provider;

import java.net.URI;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.cql2elm.model.TranslatedLibrary;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.hl7.cql.model.DataType;
import org.hl7.elm.r1.Element;
import org.hl7.elm.r1.ExpressionDef;
import org.hl7.elm.r1.ExpressionRef;
import org.hl7.elm.r1.Library;
import org.hl7.elm.r1.Retrieve;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.utility.TrackBacks;
import org.opencds.cqf.cql.ls.server.visitor.ExpressionTrackBackVisitor;

public class HoverProvider {
    private CqlTranslationManager cqlTranslationManager;

    public HoverProvider(CqlTranslationManager cqlTranslationManager) {
        this.cqlTranslationManager = cqlTranslationManager;
    }

    public Hover hover(HoverParams position) {
        URI uri = Uris.parseOrNull(position.getTextDocument().getUri());
        if (uri == null) {
            return null;
        }

        // This translates on the fly. We may want to consider maintaining
        // an ELM index to reduce the need to do retranslation.
        CqlTranslator translator = this.cqlTranslationManager.translate(uri);
        if (translator == null) {
            return null;
        }

        var elm = elementForPosition(translator.getTranslatedLibrary().getLibrary(),
                position.getPosition());

        if (elm == null) {
            return null;
        }

        MarkupContent markup = markup(elm, translator.getTranslatedLibrary());
        if (markup == null) {
            return null;
        }

        return new Hover(markup, TrackBacks.toRange(elm.getTrackbacks().get(0)));
    }

    public MarkupContent markup(Element elm, TranslatedLibrary translatedLibrary) {
        if (elm instanceof ExpressionDef) {
            return markup((ExpressionDef) elm);
        } else if (elm instanceof Retrieve) {
            return markup((Retrieve) elm);
        } else if (elm instanceof ExpressionRef) {
            var resolved = translatedLibrary.resolveExpressionRef(((ExpressionDef) elm).getName());
            return markup(resolved);
        }

        return null;
    }

    public MarkupContent markup(ExpressionDef def) {
        if (def == null || def.getExpression() == null) {
            return null;
        }

        /*
         * // This def has comments // @andtags define "Expression" returns "Whatever"
         */


        DataType resultType = def.getExpression().getResultType();
        if (resultType == null) {
            return null;
        }


        // Specifying the Markdown type as cql allows the client to apply
        // cql syntax highlighting the resulting pop-up
        String result = String.join("\n", "```cql", resultType.toString(), "```");

        return new MarkupContent("markdown", result);
    }

    public MarkupContent markup(Retrieve retrieve) {
        return null;
    }

    public Element elementForPosition(Library library, Position position) {
        var visitor = new ExpressionTrackBackVisitor();
        return visitor.visitLibrary(library, position);
    }
}
