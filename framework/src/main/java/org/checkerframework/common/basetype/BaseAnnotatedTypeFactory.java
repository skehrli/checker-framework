package org.checkerframework.common.basetype;

import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;

/**
 * A factory that extends {@link GenericAnnotatedTypeFactory} to use the default flow-sensitive
 * analysis as provided by {@link CFAnalysis}.
 */
public class BaseAnnotatedTypeFactory
    extends GenericAnnotatedTypeFactory<CFValue, CFStore, CFTransfer, CFAnalysis> {

  @SuppressWarnings("this-escape")
  public BaseAnnotatedTypeFactory(BaseTypeChecker checker, boolean useFlow) {
    super(checker, useFlow);

    // Every subclass must call postInit!
    if (this.getClass() == BaseAnnotatedTypeFactory.class) {
      this.postInit();
    }
  }

  public BaseAnnotatedTypeFactory(BaseTypeChecker checker) {
    this(checker, flowByDefault);
  }

  @Override
  protected CFAnalysis createFlowAnalysis() {
    return new CFAnalysis(checker, this);
  }
}
