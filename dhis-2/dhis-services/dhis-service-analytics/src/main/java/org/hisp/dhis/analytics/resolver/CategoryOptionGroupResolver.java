/*
 * Copyright (c) 2004-2019, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.hisp.dhis.analytics.resolver;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.hisp.dhis.common.DimensionItemType.DATA_ELEMENT_OPERAND;
import static org.hisp.dhis.expression.Expression.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hisp.dhis.category.CategoryOptionComboStore;
import org.hisp.dhis.category.CategoryOptionGroup;
import org.hisp.dhis.category.CategoryOptionGroupStore;
import org.hisp.dhis.common.BaseIdentifiableObject;
import org.hisp.dhis.common.DimensionalItemId;
import org.hisp.dhis.expression.ExpressionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Luciano Fiandesio
 */
@Service
public class CategoryOptionGroupResolver
    implements
    Resolver
{
    private final ExpressionService expressionService;

    private final CategoryOptionGroupStore categoryOptionGroupStore;

    private final CategoryOptionComboStore categoryOptionComboStore;

    public CategoryOptionGroupResolver( CategoryOptionGroupStore categoryOptionGroupStore, CategoryOptionComboStore categoryOptionComboStore,
        ExpressionService expressionService )
    {
        checkNotNull( categoryOptionGroupStore );
        checkNotNull( expressionService );
        checkNotNull( categoryOptionComboStore );

        this.categoryOptionGroupStore = categoryOptionGroupStore;
        this.categoryOptionComboStore = categoryOptionComboStore;
        this.expressionService = expressionService;
    }

    private Set<String> resolveCoCFromCog( String categoryOptionGroupUid )
    {
        return categoryOptionComboStore.getCategoryOptionCombosByGroupUid( categoryOptionGroupUid ).stream()
            .map( BaseIdentifiableObject::getUid ).collect( Collectors.toSet() );
    }

    @Override
    @Transactional( readOnly = true )
    public String resolve( String expression )
    {
        // Get a DimensionalItemId from the expression. The expression is parsed and
        // each element placed in the DimensionalItemId
        Set<DimensionalItemId> dimItemIds = expressionService.getDimensionalItemIdsInExpression( expression );

        if ( isDataElementOperand( dimItemIds ) )
        {
            List<String> resolvedExpression = new ArrayList<>(2);

            DimensionalItemId dimensionalItemId = dimItemIds.stream().findFirst().get();
            // First element is always the Data Element Id
            String dataElementUid = dimensionalItemId.getId0();

            String secondElement = dimensionalItemId.getId1();
            Optional<String> cogUid = getCategoryOptionGroupUid( secondElement );
            if ( cogUid.isPresent() )
            {
                Set<String> cocs = resolveCoCFromCog( cogUid.get() );
                resolvedExpression.add( resolve( cocs, dataElementUid ) );
                System.out.println(resolvedExpression.get(0));
            }

            String thirdElement = dimensionalItemId.getId2();
            cogUid = getCategoryOptionGroupUid( secondElement );
            if ( cogUid.isPresent() )
            {
                Set<String> cocs = resolveCoCFromCog( cogUid.get() );
                resolvedExpression.add( resolve( cocs, dataElementUid ) );
                System.out.println(resolvedExpression.get(0));
            }
        }
        return expression;
    }

    private String resolve( Set<String> cocs, String dataElementUid )
    {
        return cocs.stream().map( s -> EXP_OPEN + dataElementUid + SEPARATOR + s + EXP_CLOSE )
            .collect( Collectors.joining( "+" ) );
    }

    private Optional<String> getCategoryOptionGroupUid( String uid )
    {
        CategoryOptionGroup cog = categoryOptionGroupStore.getByUid( uid );

        return cog == null ? Optional.empty() : Optional.of( cog.getUid() );
    }

    private boolean isDataElementOperand( Set<DimensionalItemId> dimensionalItemIds )
    {
        return dimensionalItemIds.size() == 1
            && dimensionalItemIds.iterator().next().getDimensionItemType().equals( DATA_ELEMENT_OPERAND );
    }
}
