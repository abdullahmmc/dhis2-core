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

import com.google.common.collect.Sets;
import org.hisp.dhis.category.CategoryOptionCombo;
import org.hisp.dhis.category.CategoryOptionComboStore;
import org.hisp.dhis.category.CategoryOptionGroup;
import org.hisp.dhis.category.CategoryOptionGroupStore;
import org.hisp.dhis.common.CodeGenerator;
import org.hisp.dhis.common.DimensionItemType;
import org.hisp.dhis.common.DimensionalItemId;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.expression.ExpressionService;
import org.hisp.dhis.random.BeanRandomizer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

import static org.hisp.dhis.DhisConvenienceTest.createCategoryOptionGroup;
import static org.mockito.Mockito.when;

/**
 * @author Luciano Fiandesio
 */
public class CategoryOptionGroupResolverTest
{

    @Mock
    private CategoryOptionGroupStore categoryOptionGroupStore;

    @Mock
    private ExpressionService expressionService;

    @Mock
    private CategoryOptionComboStore categoryOptionComboStore;

    private Resolver resolver;

    private BeanRandomizer beanRandomizer;

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    private String elem1;

    private String elem2;

    private String elem3;

    @Before
    public void setUp()
    {
        elem1 = CodeGenerator.generateUid();
        elem2 = CodeGenerator.generateUid();
        elem3 = CodeGenerator.generateUid();
        resolver = new CategoryOptionGroupResolver( categoryOptionGroupStore, categoryOptionComboStore, expressionService );
        beanRandomizer = new BeanRandomizer();
    }

    /**
     * case: #{DEUID.COGUID.AOCUID}
     */
    @Test
    public void verifySecondElementIsCOG()
    {
        DimensionalItemId dimensionalItemId = new DimensionalItemId( DimensionItemType.DATA_ELEMENT_OPERAND, elem1,
            elem2, elem3 );

        CategoryOptionGroup categoryOptionGroup = createCategoryOptionGroup( 'A' );
        // {DEUID.COGUID.AOCUID}
        String exp = createIndicatorExp();

        when( expressionService.getDimensionalItemIdsInExpression( exp ) )
            .thenReturn( Sets.newHashSet( dimensionalItemId ) );

        when( categoryOptionGroupStore.getByUid( elem2 ) ).thenReturn( categoryOptionGroup );

        List<CategoryOptionCombo> cocs = beanRandomizer.randomObjects( CategoryOptionCombo.class, 10 );

        when( categoryOptionComboStore.getCategoryOptionCombosByGroupUid( categoryOptionGroup.getUid() ) ).thenReturn( cocs );

        String expression = resolver.resolve( exp );
        System.out.println(expression);

    }

    private String createIndicatorExp()
    {
        return String.format( "#{%s.%s.%s}", elem1, elem2, elem3 );
    }

}