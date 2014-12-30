/*
 * VariableSizeArray.scala
 * Class for a variable size array
 *
 * Created By:      Avi Pfeffer (apfeffer@cra.com)
 * Creation Date:   Oct 14, 2014
 *
 * Copyright 2014 Avrom J. Pfeffer and Charles River Analytics, Inc.
 * See http://www.cra.com or email figaro@cra.com for information.
 *
 * See http://www.github.com/p2t2/figaro for a copy of the software license.
 */

package com.cra.figaro.library.process

import com.cra.figaro.language._
import com.cra.figaro.algorithm.ValuesMaker
import com.cra.figaro.algorithm.lazyfactored._
import com.cra.figaro.algorithm.factored._

class FixedSizeArrayElement[Value](private val fsa: Element[FixedSizeArray[Value]])
extends ContainerElement[Int, Value](fsa.asInstanceOf[Element[Container[Int, Value]]]) {
  def concat(that: FixedSizeArrayElement[Value]): FixedSizeArrayElement[Value] = {
    val resultElem: Element[FixedSizeArray[Value]] =
      new Apply2("", this.fsa, that.fsa, (c1: FixedSizeArray[Value], c2: FixedSizeArray[Value]) => c1.concat(c2), this.element.universe)
    new FixedSizeArrayElement(resultElem)
  }

}

/**
 * Doc needed
 */
class MakeArray[T](name: Name[FixedSizeArray[T]], val numItems: Element[Int], val itemMaker: Int => Element[T], collection: ElementCollection)
  extends Deterministic[FixedSizeArray[T]](name, collection)
  with ValuesMaker[FixedSizeArray[T]] {
  /* MakeArray is basically an Apply, with a few differences:
   * 1. We have to register the embedded elements in the containers (i.e., the items) with the MakeArray.
   * 2. When generating values, we have to go in and generate values for the items.
   * 3. LazyValues doesn't need to store a map from the numItems value to the possible containers, because the arrays stream
   *    takes care of making sure they are always the same. Therefore, we don't need to worry about it in makeFactors.
   */

  /**
   * An infinite stream of items in the list. At any point in time, the value of this element
   * is the prefix of items specified by the value of numItems.
   */
  def makeItems(i: Int): Stream[Element[T]] = {
    val item = itemMaker(i)
    //item.makePermanent() // Since the same item is used again and again, we don't want to deactivate it
    item #:: makeItems(i + 1)
  }
  lazy val items = makeItems(0)

  def makeArrays(i: Int): Stream[FixedSizeArray[T]] = {
    val array: FixedSizeArray[T] = new FixedSizeArray(i, (j: Int) => items(j))
    array #:: makeArrays(i + 1)
  }
  lazy val arrays = makeArrays(0)

  override def args = numItems :: (items take numItems.value).toList

  override def generateValue = arrays(numItems.value)

  /**
   * Return the i-th item in the list. Throws IllegalArgumentException if i is greater
   * than the current value of numItems
   */
  def apply(i: Int) = i < numItems.value match {
    case true => items(i)
    case _ => throw new IllegalArgumentException("Invalid indices to MakeList")
  }

  def values = LazyValues(universe)

  def makeValues(depth: Int): ValueSet[FixedSizeArray[T]] = {
    // This code is subtle.
    // If we used itemMaker here, it would create bugs, as the items that appeared in the values would be different from the ones actually used by the Makelist.
    // On the other hand, if we used Values(items(0)) as a template for the values of all items, it would create other bugs, as different indices would have the same values,
    // even when the values include "new C", so they should all be different.
    // Therefore, we use Values()(items(i)) to get the possible value for each item in the stream.
    val possibleLengthValues = values(numItems, depth - 1)
    val possibleLengths = possibleLengthValues.regularValues
    val resultValues = possibleLengths.map(arrays(_))
    // Make sure to generate values for the possible items
    for { item <- items.take(possibleLengths.max) } { values(item, depth - 1) }
    val incomplete = possibleLengthValues.hasStar
    if (incomplete) ValueSet.withStar(resultValues); else ValueSet.withoutStar(resultValues)
  }

}

object VariableSizeArray {
  def apply[Value](numItems: Element[Int], generator: Int => Element[Value])
  (implicit name: Name[FixedSizeArray[Value]], collection: ElementCollection): FixedSizeArrayElement[Value] = {
    new FixedSizeArrayElement[Value](new MakeArray(name, numItems, generator, collection))
  }
}