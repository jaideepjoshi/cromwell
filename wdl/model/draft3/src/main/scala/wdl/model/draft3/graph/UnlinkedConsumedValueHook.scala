package wdl.model.draft3.graph

sealed trait UnlinkedConsumedValueHook

final case class UnlinkedIdentifierHook(name: String) extends UnlinkedConsumedValueHook

/**
  * Until we do the linking, we can't tell whether a consumed 'x.y' is a call output or a member access for 'y' on
  * a variable called 'x'.
  */
final case class UnlinkedCallOutputOrIdentifierAndMemberAccessHook(name: String,
                                                                   firstLookup: String) extends UnlinkedConsumedValueHook
