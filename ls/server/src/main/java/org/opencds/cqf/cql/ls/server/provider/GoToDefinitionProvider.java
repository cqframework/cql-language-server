package org.opencds.cqf.cql.ls.server.provider;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.cqframework.cql.cql2elm.CqlTranslator;
import org.cqframework.cql.elm.tracking.TrackBack;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.hl7.cql.model.DataType;
import org.hl7.elm.r1.*;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.service.FileContentService;
import org.opencds.cqf.cql.ls.server.utility.CursorOverlappingElements;
import org.opencds.cqf.cql.ls.server.visitor.ExpressionOverlapVisitor;
import org.opencds.cqf.cql.ls.server.visitor.ExpressionOverlapVisitorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class GoToDefinitionProvider {
    private static final Logger log = LoggerFactory.getLogger(GoToDefinitionProvider.class);
    private CqlTranslationManager cqlTranslationManager;


    public GoToDefinitionProvider(CqlTranslationManager cqlTranslationManager) {
        this.cqlTranslationManager = cqlTranslationManager;
    }

    public Either<List<? extends Location>, List<? extends LocationLink>> getDefinitionLocation(DefinitionParams params) {

        ArrayList<LocationLink> locations = new ArrayList<>();

        URI uri = Uris.parseOrNull(params.getTextDocument().getUri());
        if (uri == null) {
            return Either.forRight(locations);
        }

        // Cql handles position indexes different then VsCode
        Position initialPosition = params.getPosition();
        Position cqlPosition = new Position(initialPosition.getLine() + 1, initialPosition.getCharacter() + 1);

        // This translates on the fly. We may want to consider maintaining
        // an ELM index to reduce the need to do retranslation.
        CqlTranslator translator = this.cqlTranslationManager.translate(uri);
        if (translator == null) {
            return Either.forRight(locations);
        }

        Library library = translator.getTranslatedLibrary().getLibrary();
        Element specificElement = CursorOverlappingElements.getMostSpecificElementAtPosition(cqlPosition, library);

        LocationLink locationLink = this.getLocationLinkOfElement(specificElement, library, uri);
        if (locationLink != null) {
            locations.add(locationLink);
        }

        return Either.forRight(locations);
    }

    protected LocationLink getLocationLinkOfElement(Element element, Library currentLibrary, URI currentUri) {
        
        if (element instanceof ExpressionRef) {
            ExpressionRef elExpressionRef = (ExpressionRef) element;

            ImmutablePair<URI, Library> searchLibraryPair;

            // If libraryName exists search for it, else use current.
            if (elExpressionRef.getLibraryName() == null) {
                searchLibraryPair = new ImmutablePair<URI, Library>(currentUri, currentLibrary);
            } else {
                searchLibraryPair = findIncludedLibrary(currentLibrary, elExpressionRef.getLibraryName(), Uris.getHead(currentUri));
            }

            if (searchLibraryPair != null) {

                List<ExpressionDef> expressionDefCandidates = GoToDefinitionProvider.getExpressionDefCandidatesByName(searchLibraryPair.getRight(), elExpressionRef.getName());

                // If one exact match for either ExpressionRef or FunctionRef, go to it
                if (expressionDefCandidates.size() == 1) {
                    return GoToDefinitionProvider.getLocationLinkForExpressionDef(expressionDefCandidates.get(0), searchLibraryPair.getLeft(), elExpressionRef);
                }

                // If more than one match for FunctionRef only, search for the best match among overloads based on arguments
                else if (expressionDefCandidates.size() > 1 && element instanceof FunctionRef) {
                    FunctionRef elFunctionRef = (FunctionRef) element;
                    FunctionDef functionDefCandidate = GoToDefinitionProvider.findMatchingFunctionDefInLibrary(expressionDefCandidates, elFunctionRef);
                    if (functionDefCandidate != null) {
                        return GoToDefinitionProvider.getLocationLinkForExpressionDef(functionDefCandidate, searchLibraryPair.getLeft(), elFunctionRef);
                    }
                }
            }
        }

        return null;
    }

    protected static List<ExpressionDef> getExpressionDefCandidatesByName(Library library, String searchName) {
        return library.getStatements().getDef().stream().filter(expressionDef -> expressionDef.getName().equals(searchName)).collect(Collectors.toList());
    }

    protected static LocationLink getLocationLinkForExpressionDef(ExpressionDef foundExpressionDef, URI libraryUri, Element selectionElement) {
        Range targetRange = GoToDefinitionProvider.getRangeOfElement(foundExpressionDef);
        Range originSelectionRange = GoToDefinitionProvider.getRangeOfElement(selectionElement);
        return new LocationLink(libraryUri.toString(), targetRange, targetRange, originSelectionRange);
    }

    protected static FunctionDef findMatchingFunctionDefInLibrary(List<ExpressionDef> expressionDefCandidates, FunctionRef elFunctionRef) {

        for (ExpressionDef expressionDef : expressionDefCandidates) {
            if (expressionDef instanceof FunctionDef) {
                FunctionDef functionDefCandidate = (FunctionDef) expressionDef;

                // Should already be filtered by name but doesnt hurt
                if (!elFunctionRef.getName().equals(functionDefCandidate.getName())) {
                    continue;
                }

                // Must have same number of arguments
                if (elFunctionRef.getOperand().size() != functionDefCandidate.getOperand().size()) {
                    continue;
                }

                boolean doesMatch = true;

                for (int i = 0; i < elFunctionRef.getOperand().size(); i++) {
                    OperandDef operandDefArg = functionDefCandidate.getOperand().get(i);
                    Expression operandRefArg = elFunctionRef.getOperand().get(i);

                    doesMatch = GoToDefinitionProvider.areFunctionArgsCompatible(operandDefArg, operandRefArg);

                    if (!doesMatch) {
                        break;
                    }
                }

                if (doesMatch) {
                    return functionDefCandidate;
                }
            }
        }

        return null;
    }

    protected ImmutablePair<URI, Library> findIncludedLibrary(Library searchLibrary, String libraryName, URI cqlDirectory) {
        File foundFile = null;
        for (IncludeDef includeDef : searchLibrary.getIncludes().getDef()) {
            // Use LocalIdentifier for lookup
            if (includeDef.getLocalIdentifier().equals(libraryName)) {
                VersionedIdentifier includeIdentifier = new VersionedIdentifier()
                        .withId(includeDef.getPath())
                        .withVersion(includeDef.getVersion());

                foundFile = FileContentService.searchFolder(cqlDirectory, includeIdentifier);
                break;
            }
        }

        if (foundFile == null) {
            return null;
        }

        CqlTranslator translator = this.cqlTranslationManager.translate(foundFile.toURI());
        if (translator == null) {
            return null;
        }

        return new ImmutablePair<URI, Library>(foundFile.toURI(), translator.getTranslatedLibrary().getLibrary());

    }

    public static boolean areFunctionArgsCompatible(OperandDef functionDefArg, Expression functionRefArg) {
        DataType functionDefArgType = functionDefArg.getOperandTypeSpecifier().getResultType();
        DataType functionRefArgType = functionRefArg.getResultType();
        return functionDefArgType.isCompatibleWith(functionRefArgType);
    }

//    public static boolean compareTypeSpecifier (TypeSpecifier a, TypeSpecifier b) {
//        if (a == null || b == null || a.getClass() != b.getClass()) {
//            return false;
//        }
//
//        if (a instanceof NamedTypeSpecifier) {
//            QName aName =  ((NamedTypeSpecifier) a).getName();
//            QName bName = ((NamedTypeSpecifier) b).getName();
//            return aName.getLocalPart().equals(bName.getLocalPart()) &&  aName.getNamespaceURI().equals(bName.getNamespaceURI()) &&  aName.getPrefix().equals(bName.getPrefix());
//        } else if (a instanceof ListTypeSpecifier) {
//            ListTypeSpecifier aList = (ListTypeSpecifier) a;
//            ListTypeSpecifier bList = (ListTypeSpecifier) b;
//            return GoToDefinitionProvider.compareTypeSpecifier(aList.getElementType(), bList.getElementType());
//        } else if (a instanceof IntervalTypeSpecifier) {
//            IntervalTypeSpecifier aInterval = (IntervalTypeSpecifier) a;
//            IntervalTypeSpecifier bInterval = (IntervalTypeSpecifier) b;
//            return GoToDefinitionProvider.compareTypeSpecifier(aInterval.getPointType(), bInterval.getPointType());
//        }
//
//        return false;
//    }


    public static Range getRangeOfElement(Element element) {
        if (element.getTrackbacks().size() == 0) {
            return null;
        }
        TrackBack trackBack = element.getTrackbacks().get(0);
        Position startPosition = new Position(trackBack.getStartLine() - 1, trackBack.getStartChar() - 1);
        Position endPosition = new Position(trackBack.getEndLine() - 1, trackBack.getEndChar());
        return new Range(startPosition, endPosition);
    }

}
